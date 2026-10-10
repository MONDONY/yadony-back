package com.yadony.api.payments.events;

import java.util.UUID;

/**
 * Un paiement vient de passer {@code PENDING → ESCROW} (webhook {@code amount_capturable_updated},
 * checkout de négociation, resynchronisation Stripe). Publié dans la transaction du passage ;
 * les écouteurs agissent après commit : capture d'une négociation
 * ({@code NegotiationCaptureListener}), versement de rattrapage si le colis est déjà livré
 * ({@code DeliveredEscrowReleaser}).
 */
public class PaymentEscrowReadyEvent {

    private final UUID bidId;
    private final UUID paymentId;
    private final boolean callerSettles;

    public PaymentEscrowReadyEvent(UUID bidId, UUID paymentId) {
        this(bidId, paymentId, false);
    }

    /**
     * @param callerSettles vrai quand l'appelant capture et verse lui-même, de façon synchrone,
     *                      juste après le commit (resynchronisation d'un colis déjà livré) : les
     *                      écouteurs asynchrones ne font alors rien, pour ne pas courir avec lui
     */
    public PaymentEscrowReadyEvent(UUID bidId, UUID paymentId, boolean callerSettles) {
        this.bidId = bidId;
        this.paymentId = paymentId;
        this.callerSettles = callerSettles;
    }

    public UUID getBidId() { return bidId; }
    public UUID getPaymentId() { return paymentId; }
    public boolean isCallerSettles() { return callerSettles; }
}
