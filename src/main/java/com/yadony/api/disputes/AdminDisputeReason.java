package com.yadony.api.disputes;

/**
 * Motifs d'un litige ouvert par l'administration ({@code POST /admin/bids/{id}/disputes}).
 * Le type persisté est {@code ADMIN_<motif>} (cf. {@link DisputeTypes#adminType}) : il reste
 * sous les 50 caractères de {@code disputes.type}.
 */
public enum AdminDisputeReason {
    /** Colis abîmé à l'arrivée. */
    PARCEL_DAMAGED,
    /** Colis perdu. */
    PARCEL_LOST,
    /** Colis non remis au destinataire. */
    PARCEL_NOT_DELIVERED,
    /** Contenu différent de ce qui était déclaré. */
    CONTENT_MISMATCH,
    /** Désaccord sur le prix ou le paiement. */
    PAYMENT_DISAGREEMENT,
    /** Comportement d'une des parties. */
    PARTY_BEHAVIOUR,
    /** Autre motif, décrit dans la description. */
    OTHER
}
