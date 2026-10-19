package com.yadony.api.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnnouncementSpecification#minAvailableKg} / {@link AnnouncementSpecification#maxAvailableKg}
 * exécutées contre la base : un trajet en capacité libre ({@code KG_FREE}, availableKg technique
 * à 1) n'est plus exclu par un minimum de kilos (YADONY-FLUTTER-PROD-Z, puce « Valise 23 kg »).
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementCapacitySpecificationTest {

    @Autowired
    private AnnouncementRepository repository;

    private UUID kgFree;
    private UUID exact10;
    private UUID suitcase23;
    private UUID suitcase32;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        kgFree = persist(CapacityUnit.KG_FREE, "1");
        exact10 = persist(CapacityUnit.KG_EXACT, "10");
        suitcase23 = persist(CapacityUnit.SUITCASE_23KG, "23");
        suitcase32 = persist(CapacityUnit.SUITCASE_32KG, "32");
    }

    @Test
    void minAvailableKg_keepsKgFreeTripAndBoundedTripsWithEnoughRoom() {
        assertThat(search(AnnouncementSpecification.minAvailableKg(new BigDecimal("23"))))
                .containsExactlyInAnyOrder(kgFree, suitcase23, suitcase32);
    }

    @Test
    void minAvailableKg_excludesBoundedTripWithTooLittleRoom() {
        assertThat(search(AnnouncementSpecification.minAvailableKg(new BigDecimal("23"))))
                .doesNotContain(exact10);
    }

    @Test
    void minAvailableKg_keepsKgFreeEvenAboveAnyBoundedCapacity() {
        assertThat(search(AnnouncementSpecification.minAvailableKg(new BigDecimal("100"))))
                .containsExactly(kgFree);
    }

    @Test
    void minAvailableKg_combinedWithOtherFilters_isStillAnd() {
        Specification<AnnouncementEntity> spec = AnnouncementSpecification.minAvailableKg(new BigDecimal("23"))
                .and(AnnouncementSpecification.hasArrivalCity("Bamako"));
        assertThat(search(spec)).isEmpty();
    }

    @Test
    void minAvailableKg_countMatchesContent() {
        Specification<AnnouncementEntity> spec = AnnouncementSpecification.minAvailableKg(new BigDecimal("23"));
        assertThat(repository.count(spec)).isEqualTo(3);
    }

    @Test
    void maxAvailableKg_behaviourUnchanged_kgFreeTechnicalValueStillPasses() {
        assertThat(search(AnnouncementSpecification.maxAvailableKg(new BigDecimal("10"))))
                .containsExactlyInAnyOrder(kgFree, exact10);
    }

    private List<UUID> search(Specification<AnnouncementEntity> spec) {
        return repository.findAll(spec).stream().map(AnnouncementEntity::getId).toList();
    }

    private UUID persist(CapacityUnit unit, String availableKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setCapacityUnit(unit);
        a.setAvailableKg(new BigDecimal(availableKg));
        a.setTotalKg(new BigDecimal(availableKg));
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return repository.saveAndFlush(a).getId();
    }
}
