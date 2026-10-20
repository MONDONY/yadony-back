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

    // ─── PROD-1R : date limite stockée en UTC ─────────────────────────────────────

    /**
     * Trajet de Paris publié à 12:31Z (14:31 à Paris) avec une limite dans 1 h : 13:31 UTC.
     * L'ancienne lecture en heure de Paris la plaçait à 11:31Z, déjà passée : refus à tort
     * de {@code handover-deadline-in-past}.
     */
    @Test
    void prod1r_deadlineInOneHourUtc_parisTrip_isAccepted() {
        assertThatCode(() -> AnnouncementService.assertNotAlreadyDeparted(
                TODAY.plusDays(1), LocalTime.of(10, 0), TODAY.atTime(13, 31, 44), PARIS, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void prod1r_deadlineOneMinuteAgoUtc_isRejectedWhateverTheZone() {
        LocalDateTime oneMinuteAgo = TODAY.atTime(12, 30, 44);
        for (ZoneId zone : new ZoneId[]{PARIS, LOME, ZoneId.of("America/Toronto")}) {
            assertThatThrownBy(() -> AnnouncementService.assertNotAlreadyDeparted(
                    TODAY.plusDays(1), LocalTime.of(10, 0), oneMinuteAgo, zone, NOW))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                            org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                    .isEqualTo("handover-deadline-in-past"));
        }
    }

    // ─── validateHandoverDeadline : départ ramené en UTC ─────────────────────────

    /**
     * Toronto (UTC-4 en octobre), départ 22:00 locale = 02:00 UTC le lendemain. Une limite à
     * 20:00 locale (00:00 UTC le lendemain) est légitime : la comparaison brute avec 22:00 la
     * refusait en {@code handover-after-departure}.
     */
    @Test
    void validate_toronto_deadlineBeforeDepartureInUtc_isAccepted() {
        LocalDate dep = LocalDate.of(2026, 10, 19);
        assertThatCode(() -> AnnouncementService.validateHandoverDeadline(
                LocalDateTime.of(2026, 10, 20, 0, 0), dep, LocalTime.of(22, 0), "America/Toronto"))
                .doesNotThrowAnyException();
        assertThatCode(() -> AnnouncementService.validateHandoverDeadline(
                LocalDateTime.of(2026, 10, 20, 2, 0), dep, LocalTime.of(22, 0), "America/Toronto"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_toronto_deadlineAfterDepartureInUtc_isRejected() {
        assertThatThrownBy(() -> AnnouncementService.validateHandoverDeadline(
                LocalDateTime.of(2026, 10, 20, 2, 1), LocalDate.of(2026, 10, 19), LocalTime.of(22, 0),
                "America/Toronto"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                .isEqualTo("handover-after-departure"));
    }

    /** Paris (UTC+2) : départ 20:30 locale = 18:30 UTC ; une limite à 19:00 UTC est après. */
    @Test
    void validate_paris_deadlineComparedToDepartureInUtc() {
        LocalDate dep = LocalDate.of(2026, 10, 19);
        assertThatCode(() -> AnnouncementService.validateHandoverDeadline(
                LocalDateTime.of(2026, 10, 19, 18, 30), dep, LocalTime.of(20, 30), "Europe/Paris"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AnnouncementService.validateHandoverDeadline(
                LocalDateTime.of(2026, 10, 19, 19, 0), dep, LocalTime.of(20, 30), "Europe/Paris"))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void validate_withoutDepartureTime_boundIsEndOfLocalDay() {
        // Fin du jour à Paris (23:59:59.999 locale) = 21:59:59.999 UTC en octobre.
        LocalDate dep = LocalDate.of(2026, 10, 19);
        assertThatCode(() -> AnnouncementService.validateHandoverDeadline(
                LocalDateTime.of(2026, 10, 19, 21, 59), dep, null, "Europe/Paris"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AnnouncementService.validateHandoverDeadline(
                LocalDateTime.of(2026, 10, 19, 22, 0), dep, null, "Europe/Paris"))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void validate_missingDeadline_isRequired() {
        assertThatThrownBy(() -> AnnouncementService.validateHandoverDeadline(
                null, LocalDate.of(2026, 10, 19), null, "Europe/Paris"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                .isEqualTo("handover-deadline-required"));
    }
}
