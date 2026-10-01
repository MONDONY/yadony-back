package com.yadony.api.cancellation;

public enum CancellationReason {
    OTHER,
    SENDER_NO_SHOW,
    TRIP_CANCELLED,
    MUTUAL_AGREEMENT,
    SENDER_CANCEL_AFTER_HANDOVER,
    TRAVELER_CANCEL_AFTER_HANDOVER,
    /** Voyageur annule le transport d'un colis payé (bid ACCEPTED/PAYMENT_ESCROWED), sans annuler le trajet. */
    BID_CANCELLED_BY_TRAVELER,
    /** Voyageur refuse une demande déjà payée (bid PAYMENT_ESCROWED) via rejectBid. */
    BID_REJECTED_AFTER_PAYMENT,
    /** Système : le voyageur a supprimé son compte (hard-delete immédiat ou finalisation
     *  RGPD J+30) — cf. {@code cancellation.AccountDeletionCancellationListener}. */
    TRAVELER_ACCOUNT_DELETED,
    /** L'expéditeur se retire après le report du trajet par le voyageur : remboursement
     *  intégral, rematch, et une annulation comptée au voyageur. */
    TRIP_RESCHEDULE_WITHDRAWN
}
