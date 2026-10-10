package com.yadony.api.cancellation;

/** Motif d'une annulation de colis décidée par l'administration (trace d'audit, jamais montrée aux parties). */
public enum AdminBidCancelReason {
    /** L'expéditeur l'a demandé au support. */
    SENDER_REQUEST,
    /** Le voyageur l'a demandé au support. */
    TRAVELER_REQUEST,
    /** Le voyageur est injoignable ou ne fera pas le trajet. */
    TRAVELER_UNAVAILABLE,
    /** Contenu interdit ou non conforme. */
    PROHIBITED_CONTENT,
    /** Suspicion de fraude. */
    FRAUD_SUSPECTED,
    /** Colis en double ou créé par erreur. */
    DUPLICATE,
    /** Incident technique de la plateforme. */
    TECHNICAL_ISSUE,
    /** Autre motif : la note (au moins 10 caractères) est alors obligatoire. */
    OTHER
}
