package com.yadony.api.payments.amendment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BidAmendmentStatusTest {

    @Test
    void open_isTheFourStatesAwaitingSomething() {
        assertThat(BidAmendmentStatus.OPEN).containsExactlyInAnyOrder(
                BidAmendmentStatus.AUTHORIZATION_PENDING, BidAmendmentStatus.PROPOSED,
                BidAmendmentStatus.COMMISSION_PENDING, BidAmendmentStatus.PAYMENT_PENDING);
    }

    @Test
    void secured_isAcceptedPayoutPendingAndPaidOut() {
        assertThat(BidAmendmentStatus.SECURED).containsExactlyInAnyOrder(
                BidAmendmentStatus.ACCEPTED, BidAmendmentStatus.PAYOUT_PENDING, BidAmendmentStatus.PAID_OUT);
    }

    @Test
    void isOpenAndIsSecured_followTheSets() {
        for (BidAmendmentStatus status : BidAmendmentStatus.values()) {
            assertThat(status.isOpen()).isEqualTo(BidAmendmentStatus.OPEN.contains(status));
            assertThat(status.isSecured()).isEqualTo(BidAmendmentStatus.SECURED.contains(status));
            // Un statut ne peut être à la fois en attente et acquis.
            assertThat(status.isOpen() && status.isSecured()).isFalse();
        }
        assertThat(BidAmendmentStatus.DECLINED.isOpen()).isFalse();
        assertThat(BidAmendmentStatus.REFUNDED.isSecured()).isFalse();
    }
}
