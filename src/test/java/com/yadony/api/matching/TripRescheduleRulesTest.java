package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TripRescheduleRulesTest {

    private AnnouncementEntity trip(LocalDate date, LocalTime time) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(date);
        a.setDepartureTime(time);
        return a;
    }

    private BidEntity bid(BidStatus status, UUID pending) {
        BidEntity b = new BidEntity();
        b.setStatus(status);
        b.setPendingRescheduleId(pending);
        return b;
    }

    @Test
    void acceptedParcel_decidesUntilTheHandoverDeadline() {
        BidEntity bid = bid(BidStatus.ACCEPTED, UUID.randomUUID());
        LocalDateTime deadline = LocalDateTime.now().plusDays(2);
        bid.setHandoverDeadline(deadline);

        assertThat(TripRescheduleRules.decisionDeadline(bid, trip(LocalDate.now().plusDays(3), LocalTime.NOON)))
                .isEqualTo(deadline);
    }

    @Test
    void handedOverParcel_decidesUntilTheDeparture_endOfDayWithoutTime_inUtc() {
        LocalDate date = LocalDate.of(2026, 10, 25);
        AnnouncementEntity abidjan = trip(date, LocalTime.of(22, 0));
        abidjan.setTimezone("Africa/Abidjan");
        AnnouncementEntity abidjanNoTime = trip(date, null);
        abidjanNoTime.setTimezone("Africa/Abidjan");

        assertThat(TripRescheduleRules.decisionDeadline(bid(BidStatus.HANDED_OVER, UUID.randomUUID()), abidjan))
                .isEqualTo(date.atTime(22, 0));
        assertThat(TripRescheduleRules.decisionDeadline(bid(BidStatus.HANDED_OVER, UUID.randomUUID()), abidjanNoTime))
                .isEqualTo(date.atTime(23, 59));
    }

    /** PROD-1R : départ 22:00 heure de Toronto (UTC-4 en octobre) = 02:00 UTC le lendemain. */
    @Test
    void handedOverParcel_departureConvertedToUtc_toronto() {
        AnnouncementEntity toronto = trip(LocalDate.of(2026, 10, 19), LocalTime.of(22, 0));
        toronto.setTimezone("America/Toronto");

        assertThat(TripRescheduleRules.decisionDeadline(bid(BidStatus.HANDED_OVER, UUID.randomUUID()), toronto))
                .isEqualTo(LocalDateTime.of(2026, 10, 20, 2, 0));
    }

    /** PROD-1R : colis accepté, limite 18:30 UTC (20:30 à Paris) ; la décision reste ouverte à 16:33Z. */
    @Test
    void acceptedParcel_decisionOpenComparedInUtc() {
        BidEntity bid = bid(BidStatus.ACCEPTED, UUID.randomUUID());
        bid.setHandoverDeadline(LocalDateTime.of(2026, 10, 19, 18, 30));
        AnnouncementEntity paris = trip(LocalDate.of(2026, 10, 20), LocalTime.NOON);
        paris.setTimezone("Europe/Paris");

        assertThat(TripRescheduleRules.decisionOpen(bid, paris, java.time.Instant.parse("2026-10-19T16:33:00Z"))).isTrue();
        assertThat(TripRescheduleRules.decisionOpen(bid, paris, java.time.Instant.parse("2026-10-19T18:31:00Z"))).isFalse();
    }

    @Test
    void decisionOpen_onlyWithAPendingRescheduleOnAnEngagedParcelBeforeTheDeadline() {
        AnnouncementEntity future = trip(LocalDate.now().plusDays(3), LocalTime.NOON);

        assertThat(TripRescheduleRules.decisionOpen(bid(BidStatus.HANDED_OVER, UUID.randomUUID()), future)).isTrue();
        assertThat(TripRescheduleRules.decisionOpen(bid(BidStatus.HANDED_OVER, null), future)).isFalse();
        assertThat(TripRescheduleRules.decisionOpen(bid(BidStatus.CANCELLED, UUID.randomUUID()), future)).isFalse();
        assertThat(TripRescheduleRules.decisionOpen(bid(BidStatus.HANDED_OVER, UUID.randomUUID()), null)).isFalse();
        assertThat(TripRescheduleRules.decisionOpen(bid(BidStatus.HANDED_OVER, UUID.randomUUID()),
                trip(LocalDate.now().minusDays(1), LocalTime.NOON))).isFalse();
    }

    @Test
    void remaining_countsDownToZeroAndNeverBelow() {
        AnnouncementEntity trip = trip(LocalDate.now().plusDays(3), LocalTime.NOON);
        assertThat(TripRescheduleRules.remaining(trip)).isEqualTo(2);
        trip.setRescheduleCount(1);
        assertThat(TripRescheduleRules.remaining(trip)).isEqualTo(1);
        trip.setRescheduleCount(2);
        assertThat(TripRescheduleRules.remaining(trip)).isZero();
    }
}
