package com.yadony.api.payments.supplement;

import java.util.EnumSet;
import java.util.Set;

/** États d'un supplément (plan §2.3). Le statut d'un supplément ne modifie jamais {@code bids.status}. */
public enum BidSupplementStatus {
    COMMISSION_PENDING,
    PROPOSED,
    PAYMENT_PENDING,
    PAID,
    PAYOUT_PENDING,
    PAID_OUT,
    DECLINED,
    EXPIRED,
    WITHDRAWN,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    /** En attente d'une réponse ou d'un paiement : bloquent le scan DEPART et expirent. */
    public static final Set<BidSupplementStatus> OPEN = EnumSet.of(COMMISSION_PENDING, PROPOSED, PAYMENT_PENDING);

    /** Demande « vivante » : au plus une par colis (index uq_bid_supplements_one_active, V338). */
    public static final Set<BidSupplementStatus> ACTIVE = EnumSet.of(COMMISSION_PENDING, PROPOSED, PAYMENT_PENDING,
            PAID, PAYOUT_PENDING, PAID_OUT, REFUND_PENDING);

    /** Payé par l'expéditeur et non remboursé : compte dans les totaux « avec supplément ». */
    public static final Set<BidSupplementStatus> PAID_STATES = EnumSet.of(PAID, PAYOUT_PENDING, PAID_OUT);

    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
