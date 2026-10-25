package com.yadony.api.payments.amendment;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Échéance de réponse du voyageur : {@code min(maintenant + expiry, départ − departureMargin,
 * handoverDeadline)}, absente quand elle laisse moins de {@code minResponseWindow}.
 */
class BidAmendmentWindowTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 10, 23, 10, 0);

    private BidAmendmentProperties properties;
    private BidAmendmentWindow window;
    private BidEntity bid;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        properties = new BidAmendmentProperties();
        window = new BidAmendmentWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        bid = new BidEntity();
        announcement = new AnnouncementEntity();
    }

    @Test
    void now_isUtcClock() {
        assertThat(window.now()).isEqualTo(NOW_UTC);
    }

    @Test
    void deadline_farDeparture_isNowPlusExpiry() {
        announcement.setDepartureAt(departureIn(Duration.ofDays(10)));

        assertThat(window.deadline(bid, announcement)).contains(NOW_UTC.plusHours(48));
    }

    @Test
    void deadline_cappedByDepartureMargin() {
        announcement.setDepartureAt(departureIn(Duration.ofHours(20)));

        assertThat(window.deadline(bid, announcement)).contains(NOW_UTC.plusHours(20).minusMinutes(15));
    }

    @Test
    void deadline_emptyWhenUnderMinWindow() {
        // Départ dans 1 h 30 : échéance à 1 h 15, sous les 2 h minimum.
        announcement.setDepartureAt(departureIn(Duration.ofMinutes(90)));

        assertThat(window.deadline(bid, announcement)).isEmpty();
    }

    @Test
    void deadline_exactlyMinWindow_isOpen() {
        // Échéance pile à 2 h : la fenêtre est utile (borne incluse).
        announcement.setDepartureAt(departureIn(Duration.ofHours(2).plusMinutes(15)));

        assertThat(window.deadline(bid, announcement)).contains(NOW_UTC.plusHours(2));
    }

    @Test
    void deadline_cappedByHandoverDeadline() {
        announcement.setDepartureAt(departureIn(Duration.ofDays(10)));
        bid.setHandoverDeadline(NOW_UTC.plusHours(5));

        assertThat(window.deadline(bid, announcement)).contains(NOW_UTC.plusHours(5));
    }

    @Test
    void deadline_handoverDeadlineTooClose_isEmpty() {
        announcement.setDepartureAt(departureIn(Duration.ofDays(10)));
        bid.setHandoverDeadline(NOW_UTC.plusHours(1));

        assertThat(window.deadline(bid, announcement)).isEmpty();
    }

    @Test
    void deadline_nullHandoverDeadline_isIgnored() {
        announcement.setDepartureAt(departureIn(Duration.ofDays(10)));
        bid.setHandoverDeadline(null);

        assertThat(window.deadline(bid, announcement)).contains(NOW_UTC.plusHours(48));
    }

    @Test
    void deadline_withoutDepartureAt_usesNextMidnightInTripZone() {
        // Départ le 24/10 sans heure : repli au 25/10 00:00 Europe/Paris (CEST, UTC+2) = 24/10 22:00 UTC.
        announcement.setDepartureAt(null);
        announcement.setDepartureDate(LocalDate.of(2026, 10, 24));
        announcement.setTimezone("Europe/Paris");

        Optional<LocalDateTime> deadline = window.deadline(bid, announcement);

        assertThat(deadline).contains(LocalDateTime.of(2026, 10, 24, 22, 0).minusMinutes(15));
    }

    @Test
    void deadline_departurePassed_isEmpty() {
        announcement.setDepartureAt(departureIn(Duration.ofHours(-3)));

        assertThat(window.deadline(bid, announcement)).isEmpty();
    }

    @Test
    void deadline_noDepartureKnown_isNowPlusExpiry() {
        announcement.setDepartureAt(null);
        announcement.setDepartureDate(null);

        assertThat(window.deadline(bid, announcement)).contains(NOW_UTC.plusHours(48));
    }

    private static OffsetDateTime departureIn(Duration delay) {
        return OffsetDateTime.ofInstant(NOW.plus(delay), ZoneOffset.UTC);
    }
}
