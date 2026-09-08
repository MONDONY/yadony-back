package com.yadony.api.payments.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Publié quand le voyageur est effectivement payé, quel que soit le rail : rail carte
 * (Stripe Transfer/capture, publié directement par {@code DeliveryEventListener}) ou rail
 * mobile money (payout pawaPay, publié par {@code MobileMoneyPayoutOutcomeListener} à la
 * confirmation {@code COMPLETED} — jamais à la simple soumission, pour ne pas annoncer un
 * versement qui pourrait encore échouer côté opérateur).
 *
 * <p>{@code amount} n'a PAS la même sémantique selon le rail,
 * et c'est un fait historique volontairement non corrigé ici : le rail carte (constructeur
 * 4-arg, et {@code DeliveryEventListener} qui l'appelle) publie {@code payment.getAmount()},
 * c'est-à-dire le BRUT payé par l'expéditeur — jamais {@code amount − commission}. Le rail
 * pawaPay, lui, publie {@code op.getAmount()}, le NET réellement crédité au voyageur — mais le
 * séquestre pawaPay (le deposit) porte, comme le rail carte, sur le BRUT payé par l'expéditeur :
 * c'est le VERSEMENT (payout) qui vaut le net, la commission n'étant retenue qu'à ce moment-là,
 * jamais au dépôt (revue finale, point 9 — corrige une affirmation inverse et erronée). Un futur
 * agrégat qui lirait {@code getAmount()}
 * en pensant systématiquement lire un net surcompterait la commission à chaque livraison
 * carte. Ne pas homogénéiser cette différence en changeant le publieur Stripe : c'est un
 * comportement historique déjà en production, hors périmètre de cette tâche.
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
     * @param amount      montant du versement dans {@code currency} — sémantique dépendante du
     *                    rail, voir le Javadoc de la classe (net pour pawaPay, brut historique
     *                    pour le rail carte)
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
