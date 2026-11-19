package com.yadony.api.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trajet caché par son voyageur : absent de chaque requête publique (recherche, « passe par »,
 * zone de remise, alertes corridor), de nouveau présent après réaffichage. Le détail par
 * identifiant reste lisible (propriétaire, expéditeurs qui ont déjà un colis).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Trajet caché : exclusion de toutes les surfaces publiques")
class AnnouncementHiddenSpecificationTest {

    @Autowired
    AnnouncementRepository repository;

    private AnnouncementEntity trip(AnnouncementStatus status, boolean hidden) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(status);
        a.setHidden(hidden);
        return repository.saveAndFlush(a);
    }

    private List<UUID> searchIds(boolean includeFull) {
        Specification<AnnouncementEntity> status = includeFull
                ? AnnouncementSpecification.bookableOrUpcomingFull(LocalDate.now())
                : AnnouncementSpecification.hasStatus(AnnouncementStatus.ACTIVE);
        return repository.findAll(status.and(AnnouncementSpecification.publicOrOpenSurplus()))
                .stream().map(AnnouncementEntity::getId).toList();
    }

    @Test
    @DisplayName("recherche (ACTIVE puis includeFull avec FULL) : un trajet caché n'apparaît pas, il revient après show")
    void search_excludesHidden_thenIncludesAfterShow() {
        AnnouncementEntity active = trip(AnnouncementStatus.ACTIVE, true);
        AnnouncementEntity full = trip(AnnouncementStatus.FULL, true);
        AnnouncementEntity visible = trip(AnnouncementStatus.ACTIVE, false);

        assertThat(searchIds(false)).contains(visible.getId()).doesNotContain(active.getId());
        assertThat(searchIds(true)).contains(visible.getId()).doesNotContain(active.getId(), full.getId());

        active.setHidden(false);
        full.setHidden(false);
        repository.saveAndFlush(active);
        repository.saveAndFlush(full);

        assertThat(searchIds(false)).contains(active.getId());
        assertThat(searchIds(true)).contains(active.getId(), full.getId());
    }

    @Test
    @DisplayName("« passe par » : une étape cachée ne compte plus comme un arrêt")
    void viaCity_ignoresHiddenLeg() {
        UUID group = UUID.randomUUID();
        AnnouncementEntity first = trip(AnnouncementStatus.ACTIVE, false);
        first.setTripGroupId(group);
        first.setDepartureCity("Paris");
        first.setArrivalCity("Casablanca");
        AnnouncementEntity hiddenLeg = trip(AnnouncementStatus.ACTIVE, true);
        hiddenLeg.setTripGroupId(group);
        hiddenLeg.setDepartureCity("Casablanca");
        hiddenLeg.setArrivalCity("Dakar");
        repository.saveAllAndFlush(List.of(first, hiddenLeg));

        // Seule l'étape cachée touche Dakar : la première étape ne passe plus par Dakar.
        List<UUID> viaDakar = repository.findAll(AnnouncementSpecification.voyageStopsAt("Dakar"))
                .stream().map(AnnouncementEntity::getId).toList();
        assertThat(viaDakar).doesNotContain(first.getId());

        hiddenLeg.setHidden(false);
        repository.saveAndFlush(hiddenLeg);
        viaDakar = repository.findAll(AnnouncementSpecification.voyageStopsAt("Dakar"))
                .stream().map(AnnouncementEntity::getId).toList();
        assertThat(viaDakar).contains(first.getId());
    }

    @Test
    @DisplayName("zone de remise (recherche par rayon) : le trajet caché est exclu")
    void pickupRadius_excludesHidden() {
        AnnouncementEntity hidden = trip(AnnouncementStatus.ACTIVE, true);
        AnnouncementEntity visible = trip(AnnouncementStatus.ACTIVE, false);

        List<String> ids = repository.findIdsWithinPickupRadius(48.8566, 2.3522, 20);

        assertThat(ids).contains(visible.getId().toString()).doesNotContain(hidden.getId().toString());
    }

    @Test
    @DisplayName("alertes corridor avec zone de remise : le trajet caché est exclu")
    void corridorPickupRadius_excludesHidden() {
        AnnouncementEntity hidden = trip(AnnouncementStatus.ACTIVE, true);
        AnnouncementEntity visible = trip(AnnouncementStatus.FULL, false);

        List<AnnouncementEntity> result = repository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20);

        assertThat(result).extracting(AnnouncementEntity::getId)
                .contains(visible.getId()).doesNotContain(hidden.getId());
    }

    @Test
    @DisplayName("le détail par identifiant reste lisible pour un trajet caché")
    void findById_stillWorks() {
        AnnouncementEntity hidden = trip(AnnouncementStatus.ACTIVE, true);

        assertThat(repository.findById(hidden.getId())).get()
                .satisfies(a -> {
                    assertThat(a.isHidden()).isTrue();
                    assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
                });
    }

    @Test
    @DisplayName("entité : un trajet caché n'est ni publiquement visible ni listable (page publique, alertes)")
    void entity_hiddenIsNeitherVisibleNorListable() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setStatus(AnnouncementStatus.ACTIVE);
        assertThat(a.isPubliclyVisible()).isTrue();
        assertThat(a.isPubliclyListable()).isTrue();

        a.setHidden(true);
        assertThat(a.isPubliclyVisible()).isFalse();
        assertThat(a.isPubliclyListable()).isFalse();
        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }

    @Test
    @DisplayName("un trajet dédié à surplus ouvert reste exclu s'il est caché")
    void entity_hiddenDedicatedSurplusIsNotVisible() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setLinkedPackageRequestId(UUID.randomUUID());
        a.setSurplusPublished(true);
        a.setAvailableKg(new BigDecimal("5"));
        assertThat(a.isPubliclyVisible()).isTrue();
        a.setHidden(true);
        assertThat(a.isPubliclyVisible()).isFalse();
    }
}
