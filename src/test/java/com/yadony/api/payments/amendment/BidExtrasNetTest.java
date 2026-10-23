package com.yadony.api.payments.amendment;

import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock BidAmendmentRepository amendments;

    private BidExtrasNet extrasNet;
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        extrasNet = new BidExtrasNet(supplements, amendments);
    }

    @Test
    void paidExtrasNet_sumsSupplementsInPaidStates() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(new BigDecimal("23.50"));
        when(amendments.sumAcceptedNetByBidIdAndStatusNotIn(bidId, BidAmendmentStatus.REFUNDED_STATES))
                .thenReturn(BigDecimal.ZERO);

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("23.50");
    }

    @Test
    void paidExtrasNet_sumsAcceptedAmendmentsNotRefunded() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(BigDecimal.ZERO);
        when(amendments.sumAcceptedNetByBidIdAndStatusNotIn(bidId, BidAmendmentStatus.REFUNDED_STATES))
                .thenReturn(new BigDecimal("40.00"));

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("40.00");
    }

    @Test
    void paidExtrasNet_addsSupplementAndAmendments() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(new BigDecimal("23.50"));
        when(amendments.sumAcceptedNetByBidIdAndStatusNotIn(bidId, BidAmendmentStatus.REFUNDED_STATES))
                .thenReturn(new BigDecimal("40.25"));

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("63.75");
    }

    @Test
    void paidExtrasNet_nothingPaid_isZero() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(BigDecimal.ZERO);
        when(amendments.sumAcceptedNetByBidIdAndStatusNotIn(bidId, BidAmendmentStatus.REFUNDED_STATES))
                .thenReturn(BigDecimal.ZERO);

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("0");
    }
}
