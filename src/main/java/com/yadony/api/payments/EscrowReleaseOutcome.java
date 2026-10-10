package com.yadony.api.payments;

/**
 * Issue d'une tentative de versement d'un séquestre au voyageur
 * ({@link DeliveryEventListener}) : à la livraison, au colis non réclamé, ou en rattrapage quand le
 * paiement passe en séquestre après la livraison ({@link DeliveredEscrowReleaser}).
 */
public enum EscrowReleaseOutcome {
    /** Versement parti (Transfer Stripe, capture legacy ou payout mobile money soumis). */
    RELEASED(true, "Colis déjà livré : versement envoyé au voyageur"),
    /** Le paiement n'est pas (plus) en séquestre : rien à verser ici. */
    NOT_IN_ESCROW(false, "Paiement hors séquestre : aucun versement"),
    /** Un autre traitement a versé entre-temps (claim perdu) : rien à refaire. */
    ALREADY_RELEASED(false, "Versement déjà effectué par un autre traitement"),
    /** Litige bancaire ouvert : versement bloqué, alerte CHARGEBACK_TRANSFER_BLOCKED. */
    BLOCKED_CHARGEBACK(false, "Colis livré mais litige bancaire ouvert : versement bloqué, à trancher"),
    /** Remboursement partiel déjà passé : montant à décider, alerte PARTIAL_REFUND_HOLD. */
    BLOCKED_PARTIAL_REFUND(false, "Colis livré mais paiement partiellement remboursé : versement bloqué, montant à décider"),
    /** Voyageur gelé : versement retenu, alerte PAYOUT_HELD. */
    PAYOUT_HELD(false, "Colis livré mais voyageur gelé : versement retenu"),
    /** Compte Connect désactivé ou refusé : alerte PAYOUT_STRIPE_UNUSABLE. */
    STRIPE_ACCOUNT_UNUSABLE(false, "Colis livré mais compte Stripe du voyageur inutilisable : versement impossible"),
    /** Capture du séquestre impossible : alerte ESCROW_CAPTURE_FAILED. */
    CAPTURE_FAILED(false, "Colis livré mais capture du séquestre impossible : aucun versement"),
    /** Colis payé en espèces : pas de séquestre carte. */
    CASH(false, "Colis payé en espèces : pas de séquestre"),
    /** Aucun paiement trouvé pour ce colis. */
    NO_PAYMENT(false, "Aucun paiement trouvé pour ce colis"),
    /** Le colis n'est pas livré : le versement partira à la livraison. */
    NOT_DELIVERED(false, "");

    private final boolean released;
    private final String message;

    EscrowReleaseOutcome(boolean released, String message) {
        this.released = released;
        this.message = message;
    }

    public boolean released() {
        return released;
    }

    /** Phrase destinée à l'admin (réponse de la resynchronisation). */
    public String message() {
        return message;
    }
}
