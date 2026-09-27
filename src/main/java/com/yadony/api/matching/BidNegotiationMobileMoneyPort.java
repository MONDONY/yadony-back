package com.yadony.api.matching;

import java.util.UUID;

/**
 * Rail mobile money d'un accord de négociation de trajet, vu depuis {@code matching/}.
 * Implémenté dans {@code payments/} ({@code BidNegotiationMobileMoneyAdapter}), même
 * découpage que {@code NegotiationMobileMoneyPort} pour les demandes de colis.
 */
public interface BidNegotiationMobileMoneyPort {

    /**
     * Mène un bid mobile money PENDING à AWAITING_PAYMENT dans la transaction appelante :
     * capacité réservée, promo racheté, paiement pawaPay PENDING créé, échéance de dépôt
     * posée. Lève une {@code YadonyBusinessException} si le rail est coupé, si le voyageur ne
     * peut pas recevoir de mobile money dans la devise du trajet, ou si la capacité manque :
     * l'appelant laisse alors l'exception annuler l'accord.
     */
    void acceptAgreement(UUID bidId, UUID travelerId);
}
