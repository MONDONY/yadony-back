package com.yadony.api.payments.bankpayout;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BankPayoutStatusTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void beforeAvailability_isPendingAvailability() {
        assertThat(BankPayoutStatus.derive(null, LocalDate.of(2026, 10, 22), TODAY))
                .isEqualTo(BankPayoutStatus.PENDING_AVAILABILITY);
        assertThat(BankPayoutStatus.derive(null, null, TODAY)).isEqualTo(BankPayoutStatus.PENDING_AVAILABILITY);
    }

    @Test
    void fromAvailabilityDay_isAvailable_untilAPayoutCoversIt() {
        assertThat(BankPayoutStatus.derive(null, TODAY, TODAY)).isEqualTo(BankPayoutStatus.AVAILABLE);
        assertThat(BankPayoutStatus.derive(null, TODAY.minusDays(3), TODAY)).isEqualTo(BankPayoutStatus.AVAILABLE);
        assertThat(BankPayoutStatus.derive("canceled", TODAY.minusDays(1), TODAY)).isEqualTo(BankPayoutStatus.AVAILABLE);
        assertThat(BankPayoutStatus.derive("canceled", TODAY.plusDays(1), TODAY))
                .isEqualTo(BankPayoutStatus.PENDING_AVAILABILITY);
    }

    @Test
    void payoutStatuses() {
        assertThat(BankPayoutStatus.derive("pending", TODAY, TODAY)).isEqualTo(BankPayoutStatus.IN_TRANSIT);
        assertThat(BankPayoutStatus.derive("in_transit", TODAY, TODAY)).isEqualTo(BankPayoutStatus.IN_TRANSIT);
        assertThat(BankPayoutStatus.derive("paid", TODAY, TODAY)).isEqualTo(BankPayoutStatus.PAID);
        assertThat(BankPayoutStatus.derive("failed", TODAY, TODAY)).isEqualTo(BankPayoutStatus.FAILED);
    }
}
