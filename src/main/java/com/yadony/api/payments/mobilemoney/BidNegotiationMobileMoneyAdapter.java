package com.yadony.api.payments.mobilemoney;

import com.yadony.api.matching.BidNegotiationMobileMoneyPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implémentation du port côté {@code payments/}, comme {@code NegotiationMobileMoneyAdapter}. */
@Component
public class BidNegotiationMobileMoneyAdapter implements BidNegotiationMobileMoneyPort {

    private final MobileMoneyBidPaymentService service;

    public BidNegotiationMobileMoneyAdapter(MobileMoneyBidPaymentService service) {
        this.service = service;
    }

    @Override
    public void acceptAgreement(UUID bidId, UUID travelerId) {
        service.acceptBid(bidId, travelerId);
    }
}
