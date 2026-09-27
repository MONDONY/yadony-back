package com.yadony.api.payments.mobilemoney;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("BidNegotiationMobileMoneyAdapter — accord de négociation de trajet")
class BidNegotiationMobileMoneyAdapterTest {

    @Test
    @DisplayName("acceptAgreement délègue à acceptBid, qui porte toutes les gardes du rail")
    void acceptAgreement_delegatesToAcceptBid() {
        MobileMoneyBidPaymentService service = mock(MobileMoneyBidPaymentService.class);
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        new BidNegotiationMobileMoneyAdapter(service).acceptAgreement(bidId, travelerId);

        verify(service).acceptBid(bidId, travelerId);
    }
}
