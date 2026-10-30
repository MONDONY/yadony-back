package com.yadony.api.payments;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Sender no-show confirmé (admin ou timeout de contestation) : si le bid portait un
 * escrow Stripe, le rembourser à l'expéditeur — le colis n'a pas voyagé. Rien n'est remboursé
 * si le colis a été remis ({@link BidStatus#SENDER_SHOWED_UP}).
 * (Le pendant cash — remboursement de commission au voyageur — vit dans
 * payments/cash/CommissionRefundListener.)
 *
 * <p>Le remboursement effectif (cancel PENDING / refund ESCROW / no-op) est délégué à
 * {@link RefundProcessor} (REQUIRES_NEW par paiement) — pas de @Transactional ici.
 */
@Component
public class SenderNoShowConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(SenderNoShowConfirmedListener.class);

    private final PaymentRepository paymentRepository;
    private final RefundProcessor refundProcessor;
    private final BidRepository bidRepository;

    public SenderNoShowConfirmedListener(PaymentRepository paymentRepository,
                                         RefundProcessor refundProcessor,
                                         BidRepository bidRepository) {
        this.paymentRepository = paymentRepository;
        this.refundProcessor = refundProcessor;
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW) {
            return;
        }
        // Défense en profondeur : les sources de la confirmation (échéance, expéditeur, admin)
        // n'émettent plus l'événement sur un colis remis. S'il arrive quand même, le colis voyage :
        // rendre le séquestre paierait le transport à l'expéditeur.
        BidStatus bidStatus = bidRepository.findById(event.bidId()).map(BidEntity::getStatus).orElse(null);
        if (BidStatus.SENDER_SHOWED_UP.contains(bidStatus)) {
            log.warn("Sender no-show confirmé sur le colis {} déjà {} : aucun remboursement du séquestre",
                    event.bidId(), bidStatus);
            return;
        }
        paymentRepository.findForBid(event.bidId()).ifPresentOrElse(
                payment -> refundProcessor.processRefund(payment.getId(),
                        "PAYMENT_REFUNDED_SENDER_NO_SHOW", event.bidId(),
                        Map.of("bidId", event.bidId().toString(), "reason", "sender_no_show")),
                () -> log.debug("Sender no-show bid {} : pas de paiement Stripe — rien à rembourser",
                        event.bidId()));
    }
}
