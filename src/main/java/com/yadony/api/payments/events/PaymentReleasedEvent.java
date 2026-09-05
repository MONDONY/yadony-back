package com.yadony.api.payments.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Publié quand le voyageur est effectivement payé, quel que soit le rail : rail carte
 * (Stripe Transfer/capture, publié directement par {@code DeliveryEventListener}) ou rail
 * mobile money (payout pawaPay, publié par {@code MobileMoneyPayoutOutcomeListener} à la
 * confirmation {@code COMPLETED} — jamais à la simple soumission, pour ne pas annoncer un
 * versement qui pourrait encore échouer côté opérateur).
 */
public class PaymentReleasedEvent {
    private final UUID bidId;
    private final UUID travelerId;
    private final UUID senderId;
    private final BigDecimal amount;
    private final String currency;
    private final boolean mobileMoney;

    /** Rail carte (legacy) : montant en euros, texte historique. */
    public PaymentReleasedEvent(UUID bidId, UUID travelerId, UUID senderId, BigDecimal amount) {
        this(bidId, travelerId, senderId, amount, "EUR", false);
    }

    /**
     * @param amount      net effectivement versé au voyageur, dans {@code currency}
     * @param currency    devise du versement (« EUR » pour le rail carte, « XOF »/« XAF »… pour pawaPay)
     * @param mobileMoney vrai si ce versement est un payout pawaPay (texte et catalogue de notification distincts)
     */
    public PaymentReleasedEvent(UUID bidId, UUID travelerId, UUID senderId, BigDecimal amount,
                                String currency, boolean mobileMoney) {
        this.bidId = bidId;
        this.travelerId = travelerId;
        this.senderId = senderId;
        this.amount = amount;
        this.currency = currency;
        this.mobileMoney = mobileMoney;
    }

    public UUID getBidId()       { return bidId; }
    public UUID getTravelerId()  { return travelerId; }
    public UUID getSenderId()    { return senderId; }
    public BigDecimal getAmount(){ return amount; }
    public String getCurrency()  { return currency; }
    public boolean isMobileMoney(){ return mobileMoney; }
}
