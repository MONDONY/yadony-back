package com.yadony.api.payments;

/** Prestataire qui tient réellement l'argent d'un paiement. */
public enum PaymentRail {
    /** Carte : Stripe encaisse, Stripe verse (Transfer Connect). */
    STRIPE,
    /** Mobile money : pawaPay encaisse sur le solde yadony, qui tient le séquestre, puis verse. */
    PAWAPAY
}
