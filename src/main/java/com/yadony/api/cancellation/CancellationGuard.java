package com.yadony.api.cancellation;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;

/**
 * Verrou d'annulation (D3) partagé entre {@code BidService.cancelBid} et
 * {@code CancellationService.cancelAfterHandover}.
 *
 * <p>Règle : plus d'annulation une fois le colis en transit (ou arrivé), ni une fois le départ
 * atteint pour un colis déjà remis. Le scan TRANSIT étant facultatif, c'est ce second
 * verrou qui ferme en pratique la fenêtre pour un colis récupéré.
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
        if (bid.getStatus() == BidStatus.HANDED_OVER && hasDeparted(announcement)) {
            throw locked();
        }
    }

    /**
     * Le trajet est-il parti ? Heure de départ réelle si elle est connue ; à défaut, le
     * lendemain de la date de départ. Sans ce repli, un trajet sans {@code departureAt}
     * laissait un colis récupéré annulable (et remboursé) pendant tout le voyage, faille
     * que seul le scan TRANSIT, désormais facultatif, refermait.
     */
    public static boolean hasDeparted(AnnouncementEntity announcement) {
        if (announcement == null) {
            return false;
        }
        if (announcement.getDepartureAt() != null) {
            return !OffsetDateTime.now().isBefore(announcement.getDepartureAt());
        }
        return announcement.getDepartureDate() != null
                && announcement.getDepartureDate().isBefore(LocalDate.now());
    }

    private static YadonyBusinessException locked() {
        return new YadonyBusinessException(
                HttpStatus.CONFLICT,
                "cancel-locked",
                "Cancellation locked",
                "Le colis est en transit (ou le départ est dépassé) : annulation impossible.");
    }
}
