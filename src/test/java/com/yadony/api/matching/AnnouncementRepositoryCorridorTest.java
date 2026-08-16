package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AnnouncementRepositoryCorridorTest {

    @Autowired
    AnnouncementRepository repository;

    private AnnouncementEntity newAnnouncement(String departure, String arrival, AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity(departure);
        a.setArrivalCity(arrival);
        a.setDepartureDate(LocalDate.now().plusDays(1));
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
        return a;
    }

    @Test
    void activeRow_matchesCaseInsensitive() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE));

        List<AnnouncementEntity> result = repository.findActiveByCorridor("paris", "bamako");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDepartureCity()).isEqualTo("Paris");
        assertThat(result.get(0).getArrivalCity()).isEqualTo("Bamako");
    }

    @Test
    void fullRow_matchesCorridor() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.FULL));

        List<AnnouncementEntity> result = repository.findActiveByCorridor("Paris", "Bamako");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(AnnouncementStatus.FULL);
    }

    @Test
    void cancelledRow_doesNotMatch() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.CANCELLED));

        List<AnnouncementEntity> result = repository.findActiveByCorridor("Paris", "Bamako");

        assertThat(result).isEmpty();
    }

    @Test
    void draftRow_doesNotMatch() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.DRAFT));
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE));

        List<AnnouncementEntity> result = repository.findActiveByCorridor("Paris", "Bamako");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }

    @Test
    void completedRow_doesNotMatch() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.COMPLETED));

        List<AnnouncementEntity> result = repository.findActiveByCorridor("Paris", "Bamako");

        assertThat(result).isEmpty();
    }

    @Test
    void inProgressRow_doesNotMatch() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.IN_PROGRESS));
        assertThat(repository.findActiveByCorridor("Paris", "Bamako")).isEmpty();
    }

    @Test
    void differentCorridor_doesNotMatch() {
        repository.saveAndFlush(newAnnouncement("Lyon", "Dakar", AnnouncementStatus.ACTIVE));

        List<AnnouncementEntity> result = repository.findActiveByCorridor("Paris", "Bamako");

        assertThat(result).isEmpty();
    }

    // ── Zone de remise (haversine sur le pickup) ─────────────────────────────
    // Le pickup des annonces de test est Gare du Nord (48.880756, 2.354987).

    @Test
    void withinPickupRadius_insideZone_matches() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE));

        // Centre = Châtelet (~2,7 km du pickup), rayon 20 km → dans la zone.
        List<AnnouncementEntity> result = repository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20);

        assertThat(result).hasSize(1);
    }

    @Test
    void withinPickupRadius_outsideZone_excluded() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE));

        // Centre = Lyon (~390 km du pickup parisien), rayon 20 km → hors zone.
        List<AnnouncementEntity> result = repository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 45.7640, 4.8357, 20);

        assertThat(result).isEmpty();
    }

    @Test
    void withinPickupRadius_differentCorridor_excluded() {
        // Même pickup (dans la zone) mais corridor Lyon→Dakar ≠ Paris→Bamako.
        repository.saveAndFlush(newAnnouncement("Lyon", "Dakar", AnnouncementStatus.ACTIVE));

        List<AnnouncementEntity> result = repository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20);

        assertThat(result).isEmpty();
    }

    @Test
    void withinPickupRadius_cancelled_excluded() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.CANCELLED));

        List<AnnouncementEntity> result = repository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20);

        assertThat(result).isEmpty();
    }

    @Test
    void withinPickupRadius_draft_excluded() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.DRAFT));
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE));

        List<AnnouncementEntity> result = repository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }

    // ── findRecentByCorridor (estimation de prix publique — PriceEstimationService) ──

    @Test
    void findRecentByCorridor_draft_excluded_activeIncluded() {
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.DRAFT));
        repository.saveAndFlush(newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE));

        List<AnnouncementEntity> result = repository.findRecentByCorridor(
                "Paris", "Bamako", "EUR", org.springframework.data.domain.PageRequest.of(0, 30));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }

    @Test
    void findRecentByCorridor_filtersByCurrency_excludesOtherCurrencies() {
        // Deux annonces sur le même corridor mais en devises différentes : moyenner
        // leurs prix serait sans signification, la requête doit isoler la devise demandée.
        AnnouncementEntity eur = newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE);
        eur.setCurrency("EUR");
        repository.saveAndFlush(eur);

        AnnouncementEntity cad = newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE);
        cad.setCurrency("CAD");
        repository.saveAndFlush(cad);

        List<AnnouncementEntity> result = repository.findRecentByCorridor(
                "Paris", "Bamako", "CAD", org.springframework.data.domain.PageRequest.of(0, 30));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCurrency()).isEqualTo("CAD");
    }

    // ── findTopDestinationsForTraveler (TravelerStatsService) ──────────────────

    @Test
    void findTopDestinationsForTraveler_draft_excluded_activeIncluded() {
        UUID travelerId = UUID.randomUUID();

        AnnouncementEntity draft = newAnnouncement("Paris", "Bamako", AnnouncementStatus.DRAFT);
        draft.setTravelerId(travelerId);
        repository.saveAndFlush(draft);

        AnnouncementEntity active = newAnnouncement("Paris", "Bamako", AnnouncementStatus.ACTIVE);
        active.setTravelerId(travelerId);
        repository.saveAndFlush(active);

        List<com.yadony.api.matching.dto.TravelerStatsDto.DestinationStat> result =
                repository.findTopDestinationsForTraveler(
                        travelerId, org.springframework.data.domain.PageRequest.of(0, 100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).count()).isEqualTo(1L);
    }
}
