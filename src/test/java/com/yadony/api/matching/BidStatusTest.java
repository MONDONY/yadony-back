package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BidStatusTest {

    @Test
    void arrivedIsAcceptedOrBeyondAndEnRoute() {
        assertThat(BidStatus.ACCEPTED_OR_BEYOND).contains(BidStatus.ARRIVED);
        assertThat(BidStatus.EN_ROUTE).contains(BidStatus.ARRIVED);
    }
}
