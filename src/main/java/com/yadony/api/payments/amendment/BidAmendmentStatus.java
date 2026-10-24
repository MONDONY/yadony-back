package com.yadony.api.payments.amendment;

import java.util.EnumSet;
import java.util.Set;

/** États d'une modification de colis. Son statut ne modifie jamais {@code bids.status}. */
public enum BidAmendmentStatus {
    AUTHORIZATION_PENDING,
    PROPOSED,
    COMMISSION_PENDING,
    PAYMENT_PENDING,
    ACCEPTED,
    PAYOUT_PENDING,
    PAID_OUT,
    DECLINED,
    EXPIRED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    /**
     * En attente d'une autorisation, d'une réponse ou d'un paiement : une seule par colis
     * ({@code uq_bid_amendments_one_open}, V347), bloque la remise et expire.
     */
    public static final Set<BidAmendmentStatus> OPEN =
            EnumSet.of(AUTHORIZATION_PENDING, PROPOSED, COMMISSION_PENDING, PAYMENT_PENDING);

    /** Acceptée par le voyageur et non remboursée : compte dans les totaux et la commission différentielle. */
    public static final Set<BidAmendmentStatus> SECURED = EnumSet.of(ACCEPTED, PAYOUT_PENDING, PAID_OUT);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isSecured() {
        return SECURED.contains(this);
    }
}
