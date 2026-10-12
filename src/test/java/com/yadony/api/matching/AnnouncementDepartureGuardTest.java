package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trajet publié avec un départ déjà passé : il passait COMPLETED quelques secondes
 * après sa publication, l'expéditeur alerté restait bloqué au paiement (FLUTTER-NR).
 */
class AnnouncementDepartureGuardTest {

    private static final ZoneId LOME = ZoneId.of("Africa/Lome");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    // 12/10/2026 12:31:44 UTC, heure de publication du trajet du retour NR
    private static final Instant NOW = Instant.parse("2026-10-12T12:31:44Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Test
    void departureEarlierToday_isRejected() {
        assertThatThrownBy(() -> AnnouncementService.assertNotAlreadyDeparted(
                TODAY, LocalTime.of(9, 0), TODAY.atTime(7, 0), LOME, NOW))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                            .isEqualTo("departure-time-passed");
                });
    }

    @Test
    void departureAtExactlyNow_isRejected() {
        // 12:31:44 UTC = 14:31:44 à Paris : l'instant de départ atteint vaut « parti »
        LocalDateTime departure = LocalDateTime.ofInstant(NOW, PARIS).withNano(0);
        assertThatThrownBy(() -> AnnouncementService.assertNotAlreadyDeparted(
                departure.toLocalDate(), departure.toLocalTime(), null, PARIS, NOW))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                .isEqualTo("departure-time-passed"));
    }

    @Test
    void departureLaterToday_withDeadlineAhead_isAccepted() {
        assertThatCode(() -> AnnouncementService.assertNotAlreadyDeparted(
                TODAY, LocalTime.of(20, 0), TODAY.atTime(16, 0), LOME, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void departureTimeReadInTripZone_notServerZone() {
        // 14:00 à Paris = 12:00 UTC, déjà passé ; à Lomé (UTC) 14:00 est à venir
        assertThatThrownBy(() -> AnnouncementService.assertNotAlreadyDeparted(
                TODAY, LocalTime.of(14, 0), null, PARIS, NOW))
                .isInstanceOf(YadonyBusinessException.class);
        assertThatCode(() -> AnnouncementService.assertNotAlreadyDeparted(
                TODAY, LocalTime.of(14, 0), null, LOME, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void deadlineAlreadyPassed_withFutureDeparture_isRejected() {
        assertThatThrownBy(() -> AnnouncementService.assertNotAlreadyDeparted(
                TODAY.plusDays(2), LocalTime.of(9, 0), TODAY.atTime(8, 0), LOME, NOW))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                .isEqualTo("handover-deadline-in-past"));
    }

    @Test
    void missingTimeOrDeadline_isLeftToTheOtherRules() {
        assertThatCode(() -> AnnouncementService.assertNotAlreadyDeparted(
                TODAY, null, null, LOME, NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> AnnouncementService.assertNotAlreadyDeparted(
                null, null, null, LOME, NOW))
                .doesNotThrowAnyException();
    }
}
