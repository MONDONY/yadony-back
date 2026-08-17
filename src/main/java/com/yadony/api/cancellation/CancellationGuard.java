package com.yadony.api.cancellation;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;

/**
 * Verrou d'annulation (D3) partagé entre {@code BidService.cancelBid} et
 * {@code CancellationService.cancelAfterHandover}.
 *
 * <p>Règle : plus d'annulation une fois le colis en transit (ou arrivé), ni une fois le départ
 * réel atteint pour un colis déjà remis (backstop si le scan TRANSIT n'a jamais eu
 * lieu). Le scan TRANSIT est ainsi découplé du droit d'annuler.
 */
public final class CancellationGuard {

    private CancellationGuard() {
    }

    public static void assertCancellable(BidEntity bid, AnnouncementEntity announcement) {
        // ARRIVED est la suite immédiate de IN_TRANSIT (colis arrivé à destination,
        // en attente de retrait) : il doit être exactement aussi verrouillé, sinon
        // la fenêtre d'annulation remboursée se rouvrirait après l'arrivée.
        if (bid.getStatus() == BidStatus.IN_TRANSIT || bid.getStatus() == BidStatus.ARRIVED) {
            throw locked();
        }
        if (bid.getStatus() == BidStatus.HANDED_OVER
                && announcement != null
                && announcement.getDepartureAt() != null
                && !OffsetDateTime.now().isBefore(announcement.getDepartureAt())) {
            throw locked();
        }
    }

    private static YadonyBusinessException locked() {
        return new YadonyBusinessException(
                HttpStatus.CONFLICT,
                "cancel-locked",
                "Cancellation locked",
                "Le colis est en transit (ou le départ est dépassé) : annulation impossible.");
    }
}
