package com.yadony.api.cancellation;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * DB-level test (Task B1) : prouve que {@link RematchService#buildAlternativesSpec} filtre
 * réellement en SQL une fois évalué par Hibernate contre une vraie DB (H2, profil test) —
 * {@code RematchServiceTest} (mocké) ne prouve que la logique d'agrégation/tri en mémoire.
 * Mirrors {@code AnnouncementSurplusSpecificationTest} / {@code AnnouncementSpecificationBlockTest}
 * / {@code PackageRequestUrgentSpecificationDbTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
class RematchSpecificationDbTest {

    @Autowired
    private AnnouncementRepository repository;

    private static final BigDecimal WEIGHT_KG = new BigDecimal("5");

    private AnnouncementEntity persist(UUID travelerId, LocalDate departureDate,
                                        BigDecimal availableKg, AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(departureDate);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setAvailableKg(availableKg);
        a.setTotalKg(availableKg);
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(status);
        return repository.saveAndFlush(a);
    }

    /** cancelled = trajet annulé de référence (corridor Paris→Dakar, départ J+5). */
    private AnnouncementEntity persistCancelled(UUID cancellingTravelerId) {
        return persist(cancellingTravelerId, LocalDate.now(ZoneOffset.UTC).plusDays(5),
                new BigDecimal("20"), AnnouncementStatus.CANCELLED);
    }

    private List<AnnouncementEntity> runSpec(AnnouncementEntity cancelled) {
        LocalDate from = LocalDate.now(ZoneOffset.UTC);
        LocalDate to = cancelled.getDepartureDate().plusDays(3);
        Specification<AnnouncementEntity> spec = RematchService.buildAlternativesSpec(
                cancelled, UUID.randomUUID(), WEIGHT_KG, from, to);
        return repository.findAll(spec);
    }

    @Test
    @DisplayName("alternative valide même corridor J+1 → retournée")
    void validAlternativeSameCorridorNextDay_isIncluded() {
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity alt = persist(UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1), new BigDecimal("10"), AnnouncementStatus.ACTIVE);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId).contains(alt.getId());
    }

    @Test
    @DisplayName("date = departureDate_annulé + 3 (borne haute incluse) → retournée")
    void alternativeOnUpperBoundDate_isIncluded() {
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity onBoundary = persist(UUID.randomUUID(),
                cancelled.getDepartureDate().plusDays(3), new BigDecimal("10"), AnnouncementStatus.ACTIVE);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId).contains(onBoundary.getId());
    }

    @Test
    @DisplayName("date = departureDate_annulé + 4 → exclue")
    void alternativeJustBeyondUpperBound_isExcluded() {
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity justBeyond = persist(UUID.randomUUID(),
                cancelled.getDepartureDate().plusDays(4), new BigDecimal("10"), AnnouncementStatus.ACTIVE);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(justBeyond.getId());
    }

    @Test
    @DisplayName("date = hier (avant aujourd'hui UTC, borne basse) → exclue")
    void alternativeYesterday_isExcluded() {
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity yesterday = persist(UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).minusDays(1), new BigDecimal("10"), AnnouncementStatus.ACTIVE);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(yesterday.getId());
    }

    @Test
    @DisplayName("availableKg insuffisant (< weightKg du bid annulé) → exclue")
    void insufficientCapacity_isExcluded() {
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity tooSmall = persist(UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1), new BigDecimal("2"), AnnouncementStatus.ACTIVE);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(tooSmall.getId());
    }

    @Test
    @DisplayName("capacité libre (KG_FREE, availableKg technique à 1) → proposée malgré weightKg 5")
    void kgFreeAlternative_isIncludedDespiteTechnicalAvailableKg() {
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity kgFree = persist(UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1), BigDecimal.ONE, AnnouncementStatus.ACTIVE);
        kgFree.setCapacityUnit(com.yadony.api.matching.CapacityUnit.KG_FREE);
        repository.saveAndFlush(kgFree);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId).contains(kgFree.getId());
    }

    @Test
    @DisplayName("annonce (autre) du voyageur qui annule → exclue")
    void otherAnnouncementFromCancellingTraveler_isExcluded() {
        UUID cancellingTraveler = UUID.randomUUID();
        AnnouncementEntity cancelled = persistCancelled(cancellingTraveler);
        AnnouncementEntity otherTripSameTraveler = persist(cancellingTraveler,
                LocalDate.now(ZoneOffset.UTC).plusDays(1), new BigDecimal("10"), AnnouncementStatus.ACTIVE);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(otherTripSameTraveler.getId());
    }

    @Test
    @DisplayName("annonce CANCELLED ou DRAFT sur le même corridor/fenêtre → exclue")
    void cancelledOrDraftAnnouncement_isExcluded() {
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity otherCancelled = persist(UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1), new BigDecimal("10"), AnnouncementStatus.CANCELLED);
        AnnouncementEntity draft = persist(UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1), new BigDecimal("10"), AnnouncementStatus.DRAFT);

        List<AnnouncementEntity> results = runSpec(cancelled);

        assertThat(results).extracting(AnnouncementEntity::getId)
                .doesNotContain(otherCancelled.getId(), draft.getId());
    }

    @Test
    @DisplayName("weightKg null (bid GRID) : la Specification s'exécute sans exception et n'applique pas de filtre de capacité")
    void nullWeightKg_doesNotThrowAndSkipsCapacityFilter() {
        // Régression : bid.getWeightKg() peut être null en mode GRID (BidService — "peut être
        // null pour GRID mode"). Passer null à minAvailableKg(...) ferait lever une
        // NullPointerException à la construction du predicate JPA (cb.greaterThanOrEqualTo
        // avec un second argument null), rollback toute la transaction cancelTrip. Ce test
        // exécute la Specification contre une vraie DB (contrairement à RematchServiceTest,
        // mocké, qui n'exerce jamais le CriteriaBuilder réel) pour prouver le fix.
        AnnouncementEntity cancelled = persistCancelled(UUID.randomUUID());
        AnnouncementEntity lowCapacityAlt = persist(UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1), new BigDecimal("0.5"), AnnouncementStatus.ACTIVE);

        LocalDate from = LocalDate.now(ZoneOffset.UTC);
        LocalDate to = cancelled.getDepartureDate().plusDays(3);
        Specification<AnnouncementEntity> spec = RematchService.buildAlternativesSpec(
                cancelled, UUID.randomUUID(), null, from, to);

        List<AnnouncementEntity> results = assertDoesNotThrow(() -> repository.findAll(spec));

        // Aucun filtre de capacité appliqué → même une annonce à très faible availableKg
        // (0.5) est retournée, alors qu'elle aurait été exclue avec un weightKg non-null.
        assertThat(results).extracting(AnnouncementEntity::getId).contains(lowCapacityAlt.getId());
    }
}
