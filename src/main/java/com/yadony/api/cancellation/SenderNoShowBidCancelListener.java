package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sender no-show confirmé (par l'expéditeur lui-même, l'admin, ou le timeout de
 * contestation) : le colis n'a pas voyagé → le bid passe en {@code CANCELLED}.
 *
 * <p>Écoute {@link CancellationConfirmedEvent} de façon synchrone, dans la
 * transaction qui publie l'événement ({@code confirmSenderNoShow} ou le job
 * timeout), pour que le statut du bid soit cohérent dès le commit. Le
 * remboursement effectif reste, lui, en AFTER_COMMIT côté paiements
 * ({@code SenderNoShowConfirmedListener} / {@code CommissionRefundListener}).
 *
 * <p>Idempotent : no-op si le bid n'est plus {@code ACCEPTED} (déjà traité).
 * Ne réagit qu'au motif {@link CancellationReason#SENDER_NO_SHOW} — les
 * annulations après remise gèrent leur propre transition de statut.
 */
@Component
public class SenderNoShowBidCancelListener {

    private final BidRepository bidRepository;

    public SenderNoShowBidCancelListener(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    @EventListener
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW) {
            return;
        }
        bidRepository.findById(event.bidId()).ifPresent(bid -> {
            if (bid.getStatus() == BidStatus.ACCEPTED) {
                bid.setStatus(BidStatus.CANCELLED);
                // FLUTTER-QQ : marque l'issue sur le bid. Le contact se ferme sans la fenêtre de
                // 48 h des autres annulations et la conversation passe en lecture seule
                // (ContactWindow, messaging/), sans que ces paquets lisent l'annulation.
                bid.setRejectionReason(BidEntity.REJECTION_SENDER_NO_SHOW);
                bidRepository.save(bid);
            }
        });
    }
}
