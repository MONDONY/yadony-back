package com.yadony.api.payments.split;

/** Mécanique Stripe d'un partage, déterminée par l'état du PaymentIntent au moment de la décision. */
public enum PaymentSplitMode {
    /** PI déjà capturé : Refund partiel de la part expéditeur, puis Transfer Connect de la part voyageur. */
    REFUND_TRANSFER,
    /** PI encore autorisé : capture partielle (amount_to_capture), Stripe libère le reste, puis Transfer. */
    PARTIAL_CAPTURE
}
