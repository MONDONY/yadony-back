package com.yadony.api.payments.split;

/**
 * Avancement d'un partage. Chaque étape est commitée avant la suivante : un partage interrompu
 * (échec Stripe, crash) reprend exactement là où il s'est arrêté, sans rejouer une étape faite.
 */
public enum PaymentSplitStatus {
    /** Décision prise, paiement sorti du séquestre (claim atomique), rien encore chez Stripe. */
    CLAIMED,
    /** Part expéditeur rendue (refund partiel ou capture partielle) ; reste le transfert voyageur. */
    SENDER_REFUNDED,
    /** Les deux parts sont exécutées. */
    COMPLETED
}
