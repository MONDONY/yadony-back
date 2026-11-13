package com.yadony.api.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filtres et tri du lot bateau contre la base : volume restant, prix au m³ (comparé dans la devise
 * du trajet), arrivée estimée (fin de fourchette), et tri par prix qui range les trajets au m³
 * (sans pivot au kilo) après les trajets au kilo, dans les deux sens.
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementBoatSpecificationTest {

    private static final LocalDate DEPARTURE = LocalDate.now().plusDays(10);

    @Autowired
    private AnnouncementRepository repository;

    private UUID plane;
    private UUID boatPerKg;
    private UUID bigEur;
    private UUID smallEur;
    private UUID xof;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        // Avion au kilo : arrivée le jour du départ + 1, pas de fourchette.
        plane = repository.saveAndFlush(kilo(TransportMode.PLANE, "5", DEPARTURE.plusDays(1), null)).getId();
        // Bateau au kilo : ni volume ni prix au m³, fourchette d'arrivée.
        boatPerKg = repository.saveAndFlush(kilo(TransportMode.BOAT, "8", DEPARTURE.plusDays(20), DEPARTURE.plusDays(30))).getId();
        // Bateaux au m³ (aucun kilo, aucun pivot).
        bigEur = repository.saveAndFlush(m3("3.000", "600", "EUR", DEPARTURE.plusDays(15), DEPARTURE.plusDays(25))).getId();
        smallEur = repository.saveAndFlush(m3("0.500", "900", "EUR", DEPARTURE.plusDays(30), DEPARTURE.plusDays(40))).getId();
        xof = repository.saveAndFlush(m3("2.000", "300000", "XOF", DEPARTURE.plusDays(10), DEPARTURE.plusDays(60))).getId();
    }

    @Test
    void minVolume_excludesSmallerTripsAndPerKgTrips() {
        assertThat(search(AnnouncementSpecification.minAvailableVolume(new BigDecimal("1"))))
                .containsExactlyInAnyOrder(bigEur, xof);
        assertThat(search(AnnouncementSpecification.minAvailableVolume(new BigDecimal("0.5"))))
                .containsExactlyInAnyOrder(bigEur, smallEur, xof);
        assertThat(search(AnnouncementSpecification.minAvailableVolume(new BigDecimal("4")))).isEmpty();
    }

    @Test
    void maxPricePerM3_excludesPerKgBoatTrips_andComparesInTheTripCurrency() {
        Map<String, BigDecimal> bounds = Map.of("EUR", new BigDecimal("700"), "XOF", new BigDecimal("459169"));

        // 600 € <= 700 €, 900 € > 700 €, 300 000 XOF <= 459 169 XOF ; les trajets au kilo n'ont pas de prix au m³.
        assertThat(search(AnnouncementSpecification.maxPricePerM3(bounds)))
                .containsExactlyInAnyOrder(bigEur, xof)
                .doesNotContain(boatPerKg, plane);
    }

    @Test
    void maxPricePerM3_aCurrencyWithoutBoundNeverPasses_andAnEmptyMapKeepsNothing() {
        assertThat(search(AnnouncementSpecification.maxPricePerM3(Map.of("EUR", new BigDecimal("10000")))))
                .containsExactlyInAnyOrder(bigEur, smallEur);
        assertThat(search(AnnouncementSpecification.maxPricePerM3(Map.of()))).isEmpty();
    }

    @Test
    void arrivalBefore_usesArrivalMax() {
        // bigEur : début D+15, fin D+25. « Avant D+20 » ne doit PAS le retenir (il peut arriver D+25).
        assertThat(search(AnnouncementSpecification.arrivalNotAfter(DEPARTURE.plusDays(20))))
                .containsExactly(plane);
        assertThat(search(AnnouncementSpecification.arrivalNotAfter(DEPARTURE.plusDays(25))))
                .containsExactlyInAnyOrder(plane, bigEur);
        assertThat(search(AnnouncementSpecification.arrivalNotAfter(DEPARTURE.plusDays(60))))
                .containsExactlyInAnyOrder(plane, boatPerKg, bigEur, smallEur, xof);
    }

    @Test
    void arrivalBefore_fallsBackToArrivalDateWithoutRange_andExcludesTripsWithoutArrival() {
        AnnouncementEntity noArrival = kilo(TransportMode.PLANE, "4", null, null);
        UUID noArrivalId = repository.saveAndFlush(noArrival).getId();

        assertThat(search(AnnouncementSpecification.arrivalNotAfter(DEPARTURE.plusDays(1))))
                .containsExactly(plane)
                .doesNotContain(noArrivalId);
    }

    @Test
    void boatFilters_combineWithAnd() {
        Specification<AnnouncementEntity> spec = AnnouncementSpecification.minAvailableVolume(new BigDecimal("1"))
                .and(AnnouncementSpecification.arrivalNotAfter(DEPARTURE.plusDays(30)));
        assertThat(search(spec)).containsExactly(bigEur);
        assertThat(repository.count(spec)).isEqualTo(1);
    }

    // ── m³ et filtres existants en kilos : un trajet au m³ ne casse rien et en est exclu ──────────

    @Test
    void perM3Trips_areExcludedFromKgFilters_withoutError() {
        assertThat(search(AnnouncementSpecification.minAvailableKg(new BigDecimal("1"))))
                .containsExactlyInAnyOrder(plane, boatPerKg)
                .doesNotContain(bigEur, smallEur, xof);
        assertThat(search(AnnouncementSpecification.maxAvailableKg(new BigDecimal("100"))))
                .containsExactlyInAnyOrder(plane, boatPerKg);
        assertThat(search(AnnouncementSpecification.maxPricePerKgEur(new BigDecimal("1000"))))
                .containsExactlyInAnyOrder(plane, boatPerKg);
    }

    // ── Tri par prix : les trajets au m³ en fin, dans les deux sens ────────────────────────────

    @Test
    void priceSort_putsPerM3TripsLast_ascending() {
        List<AnnouncementEntity> page = sorted(Sort.by(Sort.Direction.ASC, "pricePerKgEur"), false);

        assertThat(ids(page.subList(0, 2))).containsExactly(plane, boatPerKg);
        assertThat(ids(page.subList(2, 5))).containsExactlyInAnyOrder(bigEur, smallEur, xof);
    }

    @Test
    void priceSort_putsPerM3TripsLast_descending() {
        List<AnnouncementEntity> page = sorted(Sort.by(Sort.Direction.DESC, "pricePerKgEur"), false);

        // Au kilo d'abord, du plus cher au moins cher ; le m³ (sans pivot) ne s'ouvre jamais la liste.
        assertThat(ids(page.subList(0, 2))).containsExactly(boatPerKg, plane);
        assertThat(ids(page.subList(2, 5))).containsExactlyInAnyOrder(bigEur, smallEur, xof);
    }

    @Test
    void priceSort_withFullTripsFirstRule_keepsBookableBeforeFull() {
        AnnouncementEntity full = kilo(TransportMode.PLANE, "1", DEPARTURE.plusDays(1), null);
        full.setStatus(AnnouncementStatus.FULL);
        full.setPricePerKgEur(new BigDecimal("99"));
        UUID fullId = repository.saveAndFlush(full).getId();

        List<AnnouncementEntity> page = sorted(Sort.by(Sort.Direction.DESC, "pricePerKgEur"), true);

        assertThat(page.get(page.size() - 1).getId()).isEqualTo(fullId);
        assertThat(ids(page.subList(0, 2))).containsExactly(boatPerKg, plane);
    }

    @Test
    void priceSort_pivotLeads_gpProOnlyBreaksTies() {
        AnnouncementEntity gpDear = kilo(TransportMode.PLANE, "10", DEPARTURE.plusDays(1), null);
        gpDear.setTravelerIsGpPro(true);
        UUID gpDearId = repository.saveAndFlush(gpDear).getId();
        AnnouncementEntity gpTie = kilo(TransportMode.PLANE, "5", DEPARTURE.plusDays(1), null);
        gpTie.setTravelerIsGpPro(true);
        UUID gpTieId = repository.saveAndFlush(gpTie).getId();

        // Prix croissant : le moins cher mène, GP Pro ou non ; à prix égal (5 €) le GP Pro passe avant.
        List<UUID> asc = ids(sorted(
                Sort.by(Sort.Direction.ASC, "pricePerKgEur").and(Sort.by(Sort.Direction.DESC, "travelerIsGpPro"))
                        .and(Sort.by(Sort.Direction.ASC, "id")), false));
        assertThat(asc.subList(0, 4)).containsExactly(gpTieId, plane, boatPerKg, gpDearId);
        assertThat(asc.subList(4, 7)).containsExactlyInAnyOrder(bigEur, smallEur, xof);
    }

    @Test
    void priceSort_countQueryStillWorks() {
        var result = repository.findAll(
                AnnouncementSpecification.unpricedLastThen(Sort.by(Sort.Direction.DESC, "pricePerKgEur"), false),
                PageRequest.of(0, 2));
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getContent()).hasSize(2);
    }

    private List<AnnouncementEntity> sorted(Sort sort, boolean bookableFirst) {
        return repository.findAll(AnnouncementSpecification.unpricedLastThen(sort, bookableFirst)
                .and(Specification.where(null)), PageRequest.of(0, 50)).getContent();
    }

    private static List<UUID> ids(List<AnnouncementEntity> list) {
        return list.stream().map(AnnouncementEntity::getId).toList();
    }

    private List<UUID> search(Specification<AnnouncementEntity> spec) {
        return repository.findAll(spec).stream().map(AnnouncementEntity::getId).toList();
    }

    private static AnnouncementEntity base(TransportMode mode, LocalDate arrival, LocalDate arrivalMax) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Marseille");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(DEPARTURE);
        a.setArrivalDate(arrival);
        a.setArrivalDateMax(arrivalMax);
        a.setTransportMode(mode);
        a.setPickupAddressLabel("Port");
        a.setPickupLat(new BigDecimal("43.300000"));
        a.setPickupLng(new BigDecimal("5.370000"));
        a.setDeliveryAddressLabel("Port de Dakar");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.430000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setCurrency("EUR");
        return a;
    }

    private static AnnouncementEntity kilo(TransportMode mode, String pricePerKg, LocalDate arrival, LocalDate arrivalMax) {
        AnnouncementEntity a = base(mode, arrival, arrivalMax);
        a.setAvailableKg(new BigDecimal("10"));
        a.setTotalKg(new BigDecimal("10"));
        a.setPricePerKg(new BigDecimal(pricePerKg));
        a.setPricePerKgEur(new BigDecimal(pricePerKg));
        return a;
    }

    private static AnnouncementEntity m3(String volume, String pricePerM3, String currency,
                                         LocalDate arrival, LocalDate arrivalMax) {
        AnnouncementEntity a = base(TransportMode.BOAT, arrival, arrivalMax);
        a.setVolumeCapacityM3(new BigDecimal(volume));
        a.setAvailableVolumeM3(new BigDecimal(volume));
        a.setPricePerM3(new BigDecimal(pricePerM3));
        a.setCurrency(currency);
        return a;
    }
}
