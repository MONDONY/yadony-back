package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recherche bateau de bout en bout dans le service (cache actif) : volume restant, prix au m³ converti
 * dans la devise du lecteur, arrivée estimée, tri par prix avec des trajets au m³ (sans pivot au kilo),
 * et cohabitation avec les filtres en kilos. Chaque paramètre doit changer la clé de cache : deux appels
 * qui ne diffèrent que par lui ne partagent jamais une entrée.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnnouncementBoatSearchIT {

    private static final LocalDate DEPARTURE = LocalDate.now().plusDays(10);

    @Autowired private AnnouncementService announcementService;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private ExchangeRateRepository exchangeRateRepository;

    private UUID travelerId;
    private UUID plane;
    private UUID boatPerKg;
    private UUID bigEur;
    private UUID smallEur;
    private UUID xof;

    @BeforeEach
    void seed() {
        cacheManager.getCache("announcements-search").clear();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        exchangeRateRepository.deleteAll();
        exchangeRateRepository.save(new ExchangeRateEntity("EUR", BigDecimal.ONE));
        exchangeRateRepository.save(new ExchangeRateEntity("XOF", new BigDecimal("655.957")));

        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("traveler-" + UUID.randomUUID());
        traveler.setFirstName("Test");
        traveler.setLastName("Bateau");
        traveler.setStatus(UserStatus.ACTIVE);
        traveler.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        traveler.setRoles(roles);
        travelerId = userRepository.save(traveler).getId();

        plane = save(kilo(TransportMode.PLANE, "5", DEPARTURE.plusDays(1), null));
        boatPerKg = save(kilo(TransportMode.BOAT, "8", DEPARTURE.plusDays(20), DEPARTURE.plusDays(30)));
        bigEur = save(m3("3.000", "600", "EUR", DEPARTURE.plusDays(15), DEPARTURE.plusDays(25)));
        smallEur = save(m3("0.500", "900", "EUR", DEPARTURE.plusDays(30), DEPARTURE.plusDays(40)));
        // 300 000 F CFA ≈ 457 EUR.
        xof = save(m3("2.000", "300000", "XOF", DEPARTURE.plusDays(10), DEPARTURE.plusDays(60)));
    }

    @Test
    void minVolume_changesTheResult_andTheCacheKey() {
        assertThat(ids(search("date", "asc", extras(bd("0.4"), null, null))))
                .containsExactlyInAnyOrder(bigEur, smallEur, xof);
        assertThat(ids(search("date", "asc", extras(bd("1"), null, null))))
                .containsExactlyInAnyOrder(bigEur, xof);
        assertThat(ids(search("date", "asc", extras(bd("2.5"), null, null)))).containsExactly(bigEur);
        // Rejoué : servi par le cache, même contenu.
        assertThat(ids(search("date", "asc", extras(bd("1"), null, null))))
                .containsExactlyInAnyOrder(bigEur, xof);
    }

    @Test
    void maxPricePerM3_isComparedInTheTripCurrency_andChangesTheCacheKey() {
        // Lecteur anonyme : EUR. 600 € passe 700, 900 € non, 300 000 F CFA (≈ 457 €) passe 700.
        assertThat(ids(search("date", "asc", extras(null, bd("700"), null))))
                .containsExactlyInAnyOrder(bigEur, xof);
        assertThat(ids(search("date", "asc", extras(null, bd("500"), null)))).containsExactly(xof);
        assertThat(ids(search("date", "asc", extras(null, bd("450"), null)))).isEmpty();
        assertThat(ids(search("date", "asc", extras(null, bd("950"), null))))
                .containsExactlyInAnyOrder(bigEur, smallEur, xof);
    }

    @Test
    void arrivalBefore_usesTheEndOfTheRange_andChangesTheCacheKey() {
        assertThat(ids(search("date", "asc", extras(null, null, DEPARTURE.plusDays(20)))))
                .containsExactly(plane);
        assertThat(ids(search("date", "asc", extras(null, null, DEPARTURE.plusDays(25)))))
                .containsExactlyInAnyOrder(plane, bigEur);
        assertThat(ids(search("date", "asc", extras(null, null, DEPARTURE.plusDays(26)))))
                .containsExactlyInAnyOrder(plane, bigEur);
        assertThat(ids(search("date", "asc", extras(null, null, DEPARTURE.plusDays(40)))))
                .containsExactlyInAnyOrder(plane, boatPerKg, bigEur, smallEur);
    }

    @Test
    void allBoatFiltersTogether_areAnd() {
        assertThat(ids(search("date", "asc",
                extras(bd("1"), bd("700"), DEPARTURE.plusDays(30))))).containsExactly(bigEur);
    }

    @Test
    void priceSortDescending_neverOpensWithPerM3Trips() {
        List<UUID> desc = ids(search("price", "desc", AnnouncementSearchExtras.NONE));
        List<UUID> asc = ids(search("price", "asc", AnnouncementSearchExtras.NONE));

        assertThat(desc).hasSize(5);
        assertThat(desc.subList(0, 2)).containsExactly(boatPerKg, plane);
        assertThat(desc.subList(2, 5)).containsExactlyInAnyOrder(bigEur, smallEur, xof);
        assertThat(asc.subList(0, 2)).containsExactly(plane, boatPerKg);
        assertThat(asc.subList(2, 5)).containsExactlyInAnyOrder(bigEur, smallEur, xof);
    }

    @Test
    void priceSortDescending_withFullTripsRequested_keepsPerM3Last() {
        AnnouncementSearchExtras withFull = new AnnouncementSearchExtras(null, Set.of(), true);
        List<UUID> desc = ids(search("price", "desc", withFull));

        assertThat(desc.subList(0, 2)).containsExactly(boatPerKg, plane);
        assertThat(desc.subList(2, 5)).containsExactlyInAnyOrder(bigEur, smallEur, xof);
    }

    @Test
    void kgFilters_andKgPriceBound_excludePerM3Trips_withoutError() {
        var perKgOnly = ids(announcementService.searchAnnouncements(
                null, null, null, null, bd("1"), bd("100"), bd("100"), null, null, null,
                null, null, null, null, null, null,
                "price", "desc", PageRequest.of(0, 20), null, null, AnnouncementSearchExtras.NONE).getContent());
        assertThat(perKgOnly).containsExactly(boatPerKg, plane);
    }

    @Test
    void kgFilter_combinedWithBoatFilter_isEmpty_sinceNoTripHasBothKgAndVolume() {
        var none = ids(announcementService.searchAnnouncements(
                null, null, null, null, bd("1"), null, null, null, null, null,
                null, null, null, null, null, null,
                "date", "asc", PageRequest.of(0, 20), null, null, extras(bd("1"), null, null)).getContent());
        assertThat(none).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private List<AnnouncementSearchResponse> search(String sortBy, String sortDir, AnnouncementSearchExtras extras) {
        return announcementService.searchAnnouncements(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                sortBy, sortDir, PageRequest.of(0, 20), null, null, extras).getContent();
    }

    private static AnnouncementSearchExtras extras(BigDecimal volume, BigDecimal price, LocalDate arrival) {
        return new AnnouncementSearchExtras(null, Set.of(), false, false, null, null, volume, price, arrival);
    }

    private static List<UUID> ids(List<AnnouncementSearchResponse> content) {
        return content.stream().map(AnnouncementSearchResponse::id).toList();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private UUID save(AnnouncementEntity a) {
        a.setTravelerId(travelerId);
        return announcementRepository.save(a).getId();
    }

    private static AnnouncementEntity base(TransportMode mode, LocalDate arrival, LocalDate arrivalMax) {
        AnnouncementEntity a = new AnnouncementEntity();
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
