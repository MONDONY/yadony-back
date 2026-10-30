package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BidStatusTest {

    @Test
    void awaiting_payment_value_exists() {
        assertThat(BidStatus.valueOf("AWAITING_PAYMENT")).isEqualTo(BidStatus.AWAITING_PAYMENT);
    }

    @Test
    void arrivedIsAcceptedOrBeyondAndEnRoute() {
        assertThat(BidStatus.ACCEPTED_OR_BEYOND).contains(BidStatus.ARRIVED);
        assertThat(BidStatus.EN_ROUTE).contains(BidStatus.ARRIVED);
    }

    @Test
    void senderShowedUp_colisRemisOuPresenteALaRemise() {
        assertThat(BidStatus.SENDER_SHOWED_UP).containsExactlyInAnyOrder(BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
                BidStatus.ARRIVED, BidStatus.COMPLETED, BidStatus.PARCEL_REFUSED);
        assertThat(BidStatus.SENDER_SHOWED_UP.contains(null)).isFalse();
    }
}
