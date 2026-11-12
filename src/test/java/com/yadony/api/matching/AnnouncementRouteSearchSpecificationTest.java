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
 * Filtres de la recherche « route » exécutés contre la base : taille minimale
 * ({@link AnnouncementSpecification#minItemSize}, strict : un trajet sans taille max n'y entre
 * pas) et ville de passage ({@link AnnouncementSpecification#voyageStopsAt}, Q1 : les trajets des
 * voyages dont une étape part de la ville ou y arrive).
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementRouteSearchSpecificationTest {

    @Autowired
    private AnnouncementRepository repository;

    private UUID sizeS;
    private UUID sizeM;
    private UUID sizeL;
    private UUID sizeXL;
    private UUID unsized;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        sizeS = persist("Paris", "Dakar", ItemSize.S, null, "10");
        sizeM = persist("Paris", "Dakar", ItemSize.M, null, "10");
        sizeL = persist("Paris", "Dakar", ItemSize.L, null, "10");
        sizeXL = persist("Paris", "Dakar", ItemSize.XL, null, "10");
        unsized = persist("Paris", "Dakar", null, null, "10");
    }

    // ─── taille minimale ────────────────────────────────────────────────────

    @Test
    void minItemSizeL_keepsLandXL_dropsMandUnsized() {
        assertThat(search(AnnouncementSpecification.minItemSize(ItemSize.L)))
                .containsExactlyInAnyOrder(sizeL, sizeXL);
    }

    @Test
    void minItemSizeS_keepsEverySizedTrip_butStillDropsUnsized() {
        assertThat(search(AnnouncementSpecification.minItemSize(ItemSize.S)))
                .containsExactlyInAnyOrder(sizeS, sizeM, sizeL, sizeXL)
                .doesNotContain(unsized);
    }

    @Test
    void minItemSizeXL_keepsOnlyXL() {
        assertThat(search(AnnouncementSpecification.minItemSize(ItemSize.XL))).containsExactly(sizeXL);
    }

    // ─── ville de passage ───────────────────────────────────────────────────

    @Test
    void voyageStopsAt_tangier_returnsBothLegsOfTheVoyage() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Tanger", null, voyage, "10");
        UUID leg2 = persist("Tanger", "Dakar", null, voyage, "10");
        UUID otherVoyageLeg = persist("Lyon", "Alger", null, UUID.randomUUID(), "10");

        assertThat(search(AnnouncementSpecification.voyageStopsAt("Tanger")))
                .containsExactlyInAnyOrder(leg1, leg2)
                .doesNotContain(otherVoyageLeg, sizeS, sizeM, sizeL, sizeXL, unsized);
    }

    @Test
    void voyageStopsAt_cityInTheMiddleOfAVoyage_keepsEveryLegOfIt() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Tanger", null, voyage, "10");
        UUID leg2 = persist("Tanger", "Casablanca", null, voyage, "10");
        UUID leg3 = persist("Casablanca", "Dakar", null, voyage, "10");

        // leg1 n'arrive pas à Casablanca ni n'en part : il entre par son voyage.
        assertThat(search(AnnouncementSpecification.voyageStopsAt("Casablanca")))
                .containsExactlyInAnyOrder(leg1, leg2, leg3);
    }

    @Test
    void voyageStopsAt_caseInsensitive() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Tanger", null, voyage, "10");
        UUID leg2 = persist("Tanger", "Dakar", null, voyage, "10");

        assertThat(search(AnnouncementSpecification.voyageStopsAt("TANGER"))).containsExactlyInAnyOrder(leg1, leg2);
        assertThat(search(AnnouncementSpecification.voyageStopsAt("tAnGeR"))).containsExactlyInAnyOrder(leg1, leg2);
    }

    @Test
    void voyageStopsAt_standaloneTrip_matchesOnItsOwnEndpoints() {
        // Hors voyage (tripGroupId nul) : départ ou arrivée seulement.
        assertThat(search(AnnouncementSpecification.voyageStopsAt("Dakar")))
                .containsExactlyInAnyOrder(sizeS, sizeM, sizeL, sizeXL, unsized);
        assertThat(search(AnnouncementSpecification.voyageStopsAt("Bamako"))).isEmpty();
    }

    @Test
    void voyageStopsAt_combinedWithDepartureCity_keepsTheLegThatLeavesThere() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Tanger", null, voyage, "10");
        persist("Tanger", "Dakar", null, voyage, "10");

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.hasDepartureCity("Paris")
                .and(AnnouncementSpecification.voyageStopsAt("Tanger"))
                .and(AnnouncementSpecification.hasArrivalCity("Tanger"));
        assertThat(search(spec)).containsExactly(leg1);
    }

    @Test
    void voyageStopsAt_cancelledLegInTheCity_voyageDoesNotSurface() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Marseille", null, voyage, "10");
        persist("Marseille", "Tanger", null, voyage, "10", AnnouncementStatus.CANCELLED);

        assertThat(search(AnnouncementSpecification.voyageStopsAt("Tanger"))).doesNotContain(leg1);
    }

    @Test
    void voyageStopsAt_draftLegInTheCity_voyageDoesNotSurface() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Marseille", null, voyage, "10");
        persist("Marseille", "Tanger", null, voyage, "10", AnnouncementStatus.DRAFT);

        assertThat(search(AnnouncementSpecification.voyageStopsAt("Tanger"))).doesNotContain(leg1);
    }

    @Test
    void voyageStopsAt_removedByAdminLegInTheCity_voyageDoesNotSurface() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Marseille", null, voyage, "10");
        persist("Marseille", "Tanger", null, voyage, "10", AnnouncementStatus.REMOVED_BY_ADMIN);

        assertThat(search(AnnouncementSpecification.voyageStopsAt("Tanger"))).doesNotContain(leg1);
    }

    @Test
    void voyageStopsAt_softDeletedLegInTheCity_voyageDoesNotSurface() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Marseille", null, voyage, "10");
        UUID deleted = persist("Marseille", "Tanger", null, voyage, "10");
        AnnouncementEntity entity = repository.findById(deleted).orElseThrow();
        entity.setDeletedAt(java.time.LocalDateTime.now());
        repository.saveAndFlush(entity);

        assertThat(search(AnnouncementSpecification.voyageStopsAt("Tanger"))).doesNotContain(leg1);
    }

    @Test
    void voyageStopsAt_activeOrFullLegInTheCity_voyageSurfaces() {
        UUID voyage = UUID.randomUUID();
        UUID leg1 = persist("Paris", "Marseille", null, voyage, "10");
        persist("Marseille", "Tanger", null, voyage, "10", AnnouncementStatus.ACTIVE);
        assertThat(search(AnnouncementSpecification.voyageStopsAt("Tanger"))).contains(leg1);

        UUID fullVoyage = UUID.randomUUID();
        UUID legA = persist("Lyon", "Alger", null, fullVoyage, "10");
        persist("Alger", "Tunis", null, fullVoyage, "10", AnnouncementStatus.FULL);
        assertThat(search(AnnouncementSpecification.voyageStopsAt("Tunis"))).contains(legA);
    }

    // ─── garde-fou (déjà vert) : le curseur de kilos n'a pas de borne haute ─

    @Test
    void minAvailableKg1000_returnsTonneTrip() {
        UUID tonne = persist("Paris", "Dakar", ItemSize.XL, null, "1000");
        assertThat(search(AnnouncementSpecification.minAvailableKg(new BigDecimal("1000"))))
                .containsExactly(tonne);
    }

    private List<UUID> search(Specification<AnnouncementEntity> spec) {
        return repository.findAll(spec).stream().map(AnnouncementEntity::getId).toList();
    }

    private UUID persist(String dep, String arr, ItemSize maxSize, UUID tripGroupId, String availableKg) {
        return persist(dep, arr, maxSize, tripGroupId, availableKg, AnnouncementStatus.ACTIVE);
    }

    private UUID persist(String dep, String arr, ItemSize maxSize, UUID tripGroupId, String availableKg,
                         AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity(dep);
        a.setArrivalCity(arr);
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.CAR);
        a.setPickupAddressLabel("Point de départ");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("Point d'arrivée");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal(availableKg));
        a.setTotalKg(new BigDecimal(availableKg));
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(status);
        a.setMaxItemSize(maxSize);
        a.setTripGroupId(tripGroupId);
        if (tripGroupId != null) {
            a.setTripLegIndex(1);
        }
        return repository.saveAndFlush(a).getId();
    }
}
