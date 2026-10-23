package com.yadony.api.payments.amendment;

import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidExtrasNetTest {

    @Mock BidSupplementRepository supplements;

    @Test
    void paidExtrasNet_sumsSupplementsInPaidStates() {
        UUID bidId = UUID.randomUUID();
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(new BigDecimal("23.50"));

        assertThat(new BidExtrasNet(supplements).paidExtrasNet(bidId)).isEqualByComparingTo("23.50");
    }

    @Test
    void paidExtrasNet_noPaidSupplement_isZero() {
        UUID bidId = UUID.randomUUID();
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(BigDecimal.ZERO);

        assertThat(new BidExtrasNet(supplements).paidExtrasNet(bidId)).isEqualByComparingTo("0");
    }
}
