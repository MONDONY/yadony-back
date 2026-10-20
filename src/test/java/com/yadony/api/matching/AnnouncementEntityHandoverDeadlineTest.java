package com.yadony.api.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementEntityHandoverDeadlineTest {

    // 30/09/2026 10:00 UTC. La date limite est stockée en UTC (YADONY-FLUTTER-PROD-1R).
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private static AnnouncementEntity announcement(String timezone, LocalDateTime deadline) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTimezone(timezone);
        a.setHandoverDeadline(deadline);
        return a;
    }

    @Test
    @DisplayName("sans date limite → jamais dépassée")
    void noDeadline_notPassed() {
        assertThat(announcement("Europe/Paris", null).isHandoverDeadlinePassed(NOW)).isFalse();
    }

    @Test
    @DisplayName("date limite la veille → dépassée")
    void deadlineYesterday_passed() {
        assertThat(announcement("Africa/Abidjan", LocalDateTime.of(2026, 9, 29, 18, 0))
                .isHandoverDeadlinePassed(NOW)).isTrue();
    }

    @Test
    @DisplayName("date limite demain → pas dépassée")
    void deadlineTomorrow_notPassed() {
        assertThat(announcement("Africa/Abidjan", LocalDateTime.of(2026, 10, 1, 18, 0))
                .isHandoverDeadlinePassed(NOW)).isFalse();
    }

    @Test
    @DisplayName("date limite stockée en UTC : le fuseau du trajet ne change rien au calcul")
    void deadlineIsUtcWhateverTheTripTimezone() {
        LocalDateTime elevenUtc = LocalDateTime.of(2026, 9, 30, 11, 0);
        // 11:00 UTC est dans une heure, à Paris comme à Abidjan ou Toronto.
        assertThat(announcement("Europe/Paris", elevenUtc).isHandoverDeadlinePassed(NOW)).isFalse();
        assertThat(announcement("Africa/Abidjan", elevenUtc).isHandoverDeadlinePassed(NOW)).isFalse();
        assertThat(announcement("America/Toronto", elevenUtc).isHandoverDeadlinePassed(NOW)).isFalse();
    }

    @Test
    @DisplayName("PROD-1R : limite 18:30 UTC (20:30 à Paris), ouverte à 16:33Z, close à 18:31Z")
    void prod1rParis() {
        AnnouncementEntity trip = announcement("Europe/Paris", LocalDateTime.of(2026, 10, 19, 18, 30));
        assertThat(trip.isHandoverDeadlinePassed(Instant.parse("2026-10-19T16:33:00Z"))).isFalse();
        assertThat(trip.isHandoverDeadlinePassed(Instant.parse("2026-10-19T18:29:59Z"))).isFalse();
        assertThat(trip.isHandoverDeadlinePassed(Instant.parse("2026-10-19T18:30:00Z"))).isTrue();
        assertThat(trip.isHandoverDeadlinePassed(Instant.parse("2026-10-19T18:31:00Z"))).isTrue();
    }

    @Test
    @DisplayName("PROD-1R, Toronto (UTC-4) : la limite 18:30 UTC n'est pas repoussée de 4 h")
    void prod1rToronto() {
        AnnouncementEntity trip = announcement("America/Toronto", LocalDateTime.of(2026, 10, 19, 18, 30));
        assertThat(trip.isHandoverDeadlinePassed(Instant.parse("2026-10-19T16:33:00Z"))).isFalse();
        assertThat(trip.isHandoverDeadlinePassed(Instant.parse("2026-10-19T18:31:00Z"))).isTrue();
    }

    @Test
    @DisplayName("PROD-1R : la garde de création de demande laisse passer à 16:33Z, refuse à 18:31Z")
    void prod1rGuard() {
        AnnouncementEntity trip = announcement("Europe/Paris", LocalDateTime.of(2026, 10, 19, 18, 30));
        HandoverDeadlineRules.assertNotPassed(trip, Instant.parse("2026-10-19T16:33:00Z"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        HandoverDeadlineRules.assertNotPassed(trip, Instant.parse("2026-10-19T18:31:00Z")))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                .hasMessageContaining("date limite");
    }

    @Test
    @DisplayName("date limite exactement maintenant → dépassée")
    void deadlineExactlyNow_passed() {
        assertThat(announcement("Africa/Abidjan", LocalDateTime.of(2026, 9, 30, 10, 0))
                .isHandoverDeadlinePassed(NOW)).isTrue();
    }

    @Test
    @DisplayName("fuseau vide ou nul : sans effet, la limite reste lue en UTC")
    void blankTimezone_irrelevant() {
        assertThat(announcement(" ", LocalDateTime.of(2026, 9, 30, 11, 0))
                .isHandoverDeadlinePassed(NOW)).isFalse();
        assertThat(announcement(null, LocalDateTime.of(2026, 9, 30, 9, 59))
                .isHandoverDeadlinePassed(NOW)).isTrue();
    }

    @Test
    @DisplayName("deadlineInstant : lecture UTC directe")
    void deadlineInstantIsUtc() {
        assertThat(HandoverDeadlineRules.deadlineInstant(LocalDateTime.of(2026, 10, 19, 18, 30)))
                .isEqualTo(Instant.parse("2026-10-19T18:30:00Z"));
    }

    @Test
    @DisplayName("page publique : la limite UTC s'affiche à l'heure de la ville de départ")
    void publicPageShowsDeadlineInDepartureTimezone() {
        LocalDateTime utc = LocalDateTime.of(2026, 10, 19, 18, 30);
        assertThat(PublicAnnouncementPageController.formatDeadline(announcement("Europe/Paris", utc)))
                .isEqualTo("lundi 19 octobre à 20h30");
        assertThat(PublicAnnouncementPageController.formatDeadline(announcement("America/Toronto", utc)))
                .isEqualTo("lundi 19 octobre à 14h30");
        assertThat(PublicAnnouncementPageController.formatDeadline(announcement("Africa/Dakar", null))).isNull();
    }
}
