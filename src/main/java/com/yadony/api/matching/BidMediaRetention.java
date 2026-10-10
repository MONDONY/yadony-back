package com.yadony.api.matching;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Règle unique de fin de conservation des médias d'un bid (photos de suivi, de demande,
 * de chat) : N jours après la livraison, ou après la fin pour un bid terminé autrement.
 * Fonction pure, sans état : partagée par tracking/, requests/ et messaging/ sans
 * injection de service.
 */
public final class BidMediaRetention {

    /** Bids encore vivants : leurs médias ne sont jamais purgés par l'échéance. */
    public static final Set<BidStatus> RUNNING_STATUSES = EnumSet.of(
            BidStatus.AWAITING_PAYMENT, BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING,
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

    private BidMediaRetention() {
    }

    public static Optional<LocalDateTime> end(BidEntity bid, int retentionDays) {
        BidStatus status = bid.getStatus();
        if (status == null || RUNNING_STATUSES.contains(status)) {
            return Optional.empty();
        }
        LocalDateTime base = status == BidStatus.COMPLETED && bid.getDeliveredAt() != null
                ? bid.getDeliveredAt() : bid.getUpdatedAt();
        return base == null ? Optional.empty() : Optional.of(base.plusDays(retentionDays));
    }
}
