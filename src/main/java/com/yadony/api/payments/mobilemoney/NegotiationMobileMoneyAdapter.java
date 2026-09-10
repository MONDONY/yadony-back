package com.yadony.api.payments.mobilemoney;

import com.yadony.api.requests.NegotiationMobileMoneyPort;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implémentation du port côté {@code payments/}, comme {@code NegotiationEscrowAdapter} pour la carte. */
@Component
public class NegotiationMobileMoneyAdapter implements NegotiationMobileMoneyPort {

    private final MobileMoneyNegotiationPaymentService service;

    public NegotiationMobileMoneyAdapter(MobileMoneyNegotiationPaymentService service) {
        this.service = service;
    }

    @Override
    public PendingDeposit createPendingDeposit(UUID threadId, UUID senderId, UUID travelerId,
                                               BigDecimal net, BigDecimal commissionRate, String currency) {
        return service.createPendingPayment(threadId, senderId, travelerId, net, commissionRate, currency);
    }

    @Override
    public ReleaseOutcome releasePendingDeposit(UUID threadId) {
        return service.releasePendingDeposit(threadId);
    }

    @Override
    public boolean refundEscrowedDeposit(UUID threadId) {
        return service.refundEscrowedDeposit(threadId);
    }
}
