package com.yadony.api.payments.reconciliation;

/**
 * Un écart entre ce que la base dit d'un mouvement d'argent et ce qu'en dit le prestataire.
 *
 * @param provider  prestataire interrogé
 * @param reference identifiant de l'objet en écart (paiement, PaymentIntent, opération pawaPay)
 * @param code      un ou plusieurs codes d'écart, séparés par des virgules (ex. MONTANT_DIFFERENT)
 * @param detail    les deux versions, en clair, pour l'admin qui tranche
 */
public record ReconciliationMismatch(Provider provider, String reference, String code, String detail) {

    public enum Provider { STRIPE, PAWAPAY }
}
