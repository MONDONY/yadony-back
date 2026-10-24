package com.yadony.api.payments.amendment;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidExtrasNetTest {

    @Mock BidSupplementRepository supplements;
    @Mock BidAmendmentRepository amendments;
    @Mock BidGridItemRepository gridItems;

    private BidExtrasNet extrasNet;
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        extrasNet = new BidExtrasNet(supplements, amendments, gridItems);
    }

    @Test
    void paidExtrasNet_sumsSupplementsInPaidStates() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(new BigDecimal("23.50"));
        when(amendments.sumNetByBidIdAndStatusIn(bidId, BidAmendmentStatus.SECURED))
                .thenReturn(BigDecimal.ZERO);

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("23.50");
    }

    @Test
    void paidExtrasNet_sumsSecuredAmendments() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(BigDecimal.ZERO);
        when(amendments.sumNetByBidIdAndStatusIn(bidId, BidAmendmentStatus.SECURED))
                .thenReturn(new BigDecimal("40.00"));

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("40.00");
    }

    @Test
    void paidExtrasNet_addsSupplementAndAmendments() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(new BigDecimal("23.50"));
        when(amendments.sumNetByBidIdAndStatusIn(bidId, BidAmendmentStatus.SECURED))
                .thenReturn(new BigDecimal("40.25"));

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("63.75");
    }

    @Test
    void paidExtrasNet_nothingPaid_isZero() {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(BigDecimal.ZERO);
        when(amendments.sumNetByBidIdAndStatusIn(bidId, BidAmendmentStatus.SECURED))
                .thenReturn(BigDecimal.ZERO);

        assertThat(extrasNet.paidExtrasNet(bidId)).isEqualByComparingTo("0");
    }

    @Test
    void dueNet_isPrincipalPlusPaidExtras() {
        BidEntity bid = bid("2.00");
        BidGridItemEntity phone = new BidGridItemEntity();
        phone.setUnitPriceNetSnapshot(new BigDecimal("10.00"));
        phone.setQuantity(1);
        when(gridItems.findByBidId(bidId)).thenReturn(List.of(phone));
        stubPaid("20.00", "5.00");

        // 2 kg × 4,50 + 10 (article figé) + 20 (supplément) + 5 (modification).
        assertThat(extrasNet.dueNet(bid, announcement())).isEqualByComparingTo("44.00");
    }

    @Test
    void dueNet_negotiatedBid_doesNotReloadGridItems() {
        BidEntity bid = bid("2.00");
        bid.setNegotiatedNetEur(new BigDecimal("50.00"));
        stubPaid("20.00", "0");

        // L'accord figé EST le total : relire les articles les compterait deux fois.
        assertThat(extrasNet.dueNet(bid, announcement())).isEqualByComparingTo("70.00");
        verify(gridItems, never()).findByBidId(any());
    }

    private void stubPaid(String supplement, String amendment) {
        when(supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES))
                .thenReturn(new BigDecimal(supplement));
        when(amendments.sumNetByBidIdAndStatusIn(bidId, BidAmendmentStatus.SECURED))
                .thenReturn(new BigDecimal(amendment));
    }

    private BidEntity bid(String weightKg) {
        BidEntity bid = new BidEntity();
        BidAmendmentTestSupport.setId(bid, bidId);
        bid.setWeightKg(new BigDecimal(weightKg));
        return bid;
    }

    private static AnnouncementEntity announcement() {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setPricePerKg(new BigDecimal("4.50"));
        return announcement;
    }
}
