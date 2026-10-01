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
    void handedOverParcel_decidesUntilTheDeparture_endOfDayWithoutTime() {
        LocalDate date = LocalDate.now().plusDays(3);

        assertThat(TripRescheduleRules.decisionDeadline(bid(BidStatus.HANDED_OVER, UUID.randomUUID()),
                trip(date, LocalTime.of(22, 0)))).isEqualTo(date.atTime(22, 0));
        assertThat(TripRescheduleRules.decisionDeadline(bid(BidStatus.HANDED_OVER, UUID.randomUUID()),
                trip(date, null))).isEqualTo(date.atTime(23, 59));
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
