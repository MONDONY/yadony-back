package com.yadony.api.tracking;

import com.yadony.api.matching.BidStatus;

/**
 * Statuts que le super-admin peut imposer à un colis en cours de transport. Volontairement
 * limités à l'étape logistique : l'annulation (remboursement) et les refus ont leurs propres
 * gestes admin, qui règlent l'argent.
 */
public enum AdminForcedBidStatus {
    HANDED_OVER(BidStatus.HANDED_OVER),
    IN_TRANSIT(BidStatus.IN_TRANSIT),
    ARRIVED(BidStatus.ARRIVED),
    /** Livré : même effet qu'une livraison confirmée par le code (versement, notifications, stats). */
    COMPLETED(BidStatus.COMPLETED);

    private final BidStatus bidStatus;

    AdminForcedBidStatus(BidStatus bidStatus) {
        this.bidStatus = bidStatus;
    }

    public BidStatus bidStatus() {
        return bidStatus;
    }
}
