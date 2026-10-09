package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PickupCodesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 10, 0);

    private static BidEntity bid(BidStatus status, String code, LocalDateTime expiry) {
        BidEntity bid = new BidEntity();
        bid.setStatus(status);
        bid.setConfirmationCode(code);
        bid.setConfirmationCodeExpiry(expiry);
        return bid;
    }

    @Test
    void newCode_isSixDigits() {
        assertThat(PickupCodes.newCode()).matches("\\d{6}");
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void renewalNeeded_whenCodeMissingBlankOrExpired(BidStatus status) {
        assertThat(PickupCodes.renewalNeeded(bid(status, null, null), NOW)).isTrue();
        assertThat(PickupCodes.renewalNeeded(bid(status, " ", null), NOW)).isTrue();
        assertThat(PickupCodes.renewalNeeded(bid(status, "123456", NOW.minusSeconds(1)), NOW)).isTrue();
    }

    @Test
    void noRenewal_whenCodeStillValid() {
        assertThat(PickupCodes.renewalNeeded(bid(BidStatus.IN_TRANSIT, "123456", NOW.plusMinutes(1)), NOW)).isFalse();
        assertThat(PickupCodes.renewalNeeded(bid(BidStatus.IN_TRANSIT, "123456", NOW), NOW)).isFalse();
        assertThat(PickupCodes.renewalNeeded(bid(BidStatus.IN_TRANSIT, "123456", null), NOW)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED"}, mode = EnumSource.Mode.EXCLUDE)
    void noRenewal_whenParcelNotWithTraveler(BidStatus status) {
        assertThat(PickupCodes.renewalNeeded(bid(status, null, null), NOW)).isFalse();
    }

    @Test
    void noRenewal_forNullBid() {
        assertThat(PickupCodes.renewalNeeded(null, NOW)).isFalse();
    }
}
