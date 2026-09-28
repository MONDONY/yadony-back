package com.yadony.api.payments.hold;

/**
 * Motif d'un gel des versements d'un voyageur. Seuls deux motifs existent (decision produit) :
 * une suspension ne gele rien.
 *
 * <p>L'ordre de declaration est l'ordre d'affichage quand les deux coexistent : le motif
 * principal expose au back-office est le premier present.
 */
public enum PayoutHoldReason {
    /** Compte banni par un administrateur. Leve par la levee du bannissement. */
    BANNED,
    /** Verification d'identite retiree par un administrateur. Levee par une nouvelle verification. */
    KYC_REVOKED
}
