package com.yadony.api.cancellation;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
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

    /** Statuts d'un colis déposé : {@link PayoutTrigger#DEPOSITED_STATUSES} (définition unique). */
    static final Set<BidStatus> DEPOSITED_STATUSES = PayoutTrigger.DEPOSITED_STATUSES;

    private CancellationGuard() {
    }

    /**
     * Colis payé au dépôt ({@link PayoutTrigger#DEPOSIT}, figé à l'acceptation), déposé, et dont la plateforme a
     * RÉELLEMENT versé de l'argent au transporteur ({@code payouts}) : jamais sur la seule règle figée, jamais pour un
     * principal en espèces. La règle et le statut sont lus d'abord : un colis payé à la livraison, ou pas encore
     * déposé, ne déclenche aucune lecture de paiement.
     *
     * @param payouts {@code null} vaut {@link DepositPayoutPort#ASSUME_PAID} (repli prudent)
     */
    public static boolean depositPaid(BidEntity bid, DepositPayoutPort payouts) {
        if (bid == null || bid.getId() == null || bid.getPayoutTrigger() != PayoutTrigger.DEPOSIT
                || !DEPOSITED_STATUSES.contains(bid.getStatus())) {
            return false;
        }
        return (payouts == null ? DepositPayoutPort.ASSUME_PAID : payouts).paidAtDeposit(bid.getId());
    }

    /**
     * Annulation, refus du colis : 409 {@code deposit-paid-no-refund} si le transporteur a déjà été payé au dépôt
     * ({@link #depositPaid}). Aucun remboursement automatique après ce versement : le support ouvre un litige.
     */
    public static void assertNoRefundAfterDepositPayout(BidEntity bid, DepositPayoutPort payouts) {
        if (depositPaid(bid, payouts)) {
            throw depositPaidNoRefund();
        }
    }

    /** 409 {@code deposit-paid-no-refund} : le transporteur a déjà été payé au dépôt du colis. */
    public static YadonyBusinessException depositPaidNoRefund() {
        return YadonyBusinessException.localized(HttpStatus.CONFLICT, "deposit-paid-no-refund",
                "problem.deposit-paid-no-refund");
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
        return YadonyBusinessException.localized(HttpStatus.CONFLICT, "cancel-locked", "problem.cancel-locked");
    }
}
