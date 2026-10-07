package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Règle « le trajet est-il parti ? » partagée par la bascule IN_PROGRESS et la
 * confirmation de livraison (FLUTTER-CB). Horloge passée en paramètre : déterministe.
 */
class DepartureRulesTest {

    private static AnnouncementEntity trip(LocalDate date, LocalTime time, String zone) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(date);
        a.setDepartureTime(time);
        a.setTimezone(zone);
        return a;
    }

    // Recette : trajet du 06/10 06:00 à Paris (UTC+2 en octobre) = 04:00Z.
    private final AnnouncementEntity recette =
            trip(LocalDate.of(2026, 10, 6), LocalTime.of(6, 0), "Europe/Paris");

    @Test
    void codeSaisiLaVeille_pasParti() {
        assertThat(DepartureRules.hasDeparted(recette, Instant.parse("2026-10-05T04:36:00Z"))).isFalse();
    }

    @Test
    void frontiereExacte_partiALInstantDuDepart() {
        assertThat(DepartureRules.hasDeparted(recette, Instant.parse("2026-10-06T04:00:00Z"))).isTrue();
    }

    @Test
    void frontiereExacte_uneNanosecondeAvant_pasParti() {
        assertThat(DepartureRules.hasDeparted(recette,
                Instant.parse("2026-10-06T04:00:00Z").minusNanos(1))).isFalse();
    }

    @Test
    void fuseauDuTrajet_dakarUtc() {
        AnnouncementEntity dakar = trip(LocalDate.of(2026, 10, 6), LocalTime.of(6, 0), "Africa/Dakar");
        // 06:00 à Dakar = 06:00Z ; lu à Paris ce serait 04:00Z.
        assertThat(DepartureRules.hasDeparted(dakar, Instant.parse("2026-10-06T05:00:00Z"))).isFalse();
        assertThat(DepartureRules.hasDeparted(dakar, Instant.parse("2026-10-06T06:00:00Z"))).isTrue();
    }

    @Test
    void fuseauAbsentOuInvalide_europeParisParDefaut() {
        AnnouncementEntity sansFuseau = trip(LocalDate.of(2026, 10, 6), LocalTime.of(6, 0), null);
        AnnouncementEntity invalide = trip(LocalDate.of(2026, 10, 6), LocalTime.of(6, 0), "Mars/Olympus");
        AnnouncementEntity vide = trip(LocalDate.of(2026, 10, 6), LocalTime.of(6, 0), " ");
        Instant justAvant = Instant.parse("2026-10-06T03:59:59Z");
        Instant pile = Instant.parse("2026-10-06T04:00:00Z");
        for (AnnouncementEntity a : new AnnouncementEntity[]{sansFuseau, invalide, vide}) {
            assertThat(DepartureRules.hasDeparted(a, justAvant)).isFalse();
            assertThat(DepartureRules.hasDeparted(a, pile)).isTrue();
        }
    }

    @Test
    void sansHeure_partiAuLendemainMinuitDansLeFuseauDuTrajet() {
        AnnouncementEntity sansHeure = trip(LocalDate.of(2026, 10, 6), null, "Africa/Dakar");
        assertThat(DepartureRules.hasDeparted(sansHeure, Instant.parse("2026-10-06T23:59:59Z"))).isFalse();
        assertThat(DepartureRules.hasDeparted(sansHeure, Instant.parse("2026-10-07T00:00:00Z"))).isTrue();
    }

    @Test
    void sansDate_jamaisParti() {
        assertThat(DepartureRules.hasDeparted(trip(null, LocalTime.NOON, "Europe/Paris"), Instant.now())).isFalse();
    }
}
