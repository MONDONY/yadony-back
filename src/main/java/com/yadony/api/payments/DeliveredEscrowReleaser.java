package com.yadony.api.payments;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Versement de rattrapage d'un séquestre arrivé APRÈS la livraison.
 *
 * <p>La livraison ({@link DeliveryEventListener}) ne verse qu'un paiement {@code ESCROW}. Si le
 * paiement est encore {@code PENDING} à ce moment (webhook {@code amount_capturable_updated} pas
 * encore reçu, ou jamais : staging jusqu'au 09/10), la livraison n'envoie rien et ne repassera
 * plus. Quand le paiement passe ensuite en séquestre (webhook tardif, resynchronisation admin,
 * job {@link PendingCardPaymentAutoHealJob}), ce composant constate que le colis est déjà livré
 * et lance le même versement que la livraison ({@link DeliveryEventListener#releaseAfterLateEscrow}) :
 * mêmes gardes (litige bancaire, remboursement partiel, voyageur gelé, compte Connect
 * inutilisable), capture d'abord ({@link EscrowCaptureService}), claim {@code markReleasedIfEscrow},
 * clé Stripe {@code transfer-<paymentId>}. Plus besoin de « forcer le versement » à la main.
 *
 * <p>« Livré » = colis {@code COMPLETED} (code de retrait validé, {@code DeliveryConfirmedEvent}
 * publié), retrouvé par {@code bid_id} ou, pour une négociation, par le colis rattaché au fil.
 */
@Component
public class DeliveredEscrowReleaser {

    private static final Logger log = LoggerFactory.getLogger(DeliveredEscrowReleaser.class);

    /** Colis livré dont le séquestre peut être versé : identifiants nécessaires au versement. */
    public record DeliveredBid(UUID bidId, UUID senderId, UUID travelerId) {}

    private final PaymentRepository paymentRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final DeliveryEventListener deliveryRelease;

    public DeliveredEscrowReleaser(PaymentRepository paymentRepository, BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   DeliveryEventListener deliveryRelease) {
        this.paymentRepository = paymentRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.deliveryRelease = deliveryRelease;
    }

    /**
     * Après le commit d'un passage en séquestre : versement de rattrapage si le colis est déjà
     * livré. Asynchrone (aucun appel Stripe sur le thread du webhook ni sous sa transaction).
     * Ignoré quand l'appelant verse lui-même ({@link PaymentEscrowReadyEvent#isCallerSettles()}).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onEscrowReady(PaymentEscrowReadyEvent event) {
        if (event.isCallerSettles()) {
            return;
        }
        try {
            EscrowReleaseOutcome outcome = releaseIfDelivered(event.getPaymentId(), DeliveryEventListener.SOURCE_LATE_ESCROW);
            if (outcome != EscrowReleaseOutcome.NOT_DELIVERED) {
                log.info("Séquestre tardif du paiement {} (colis déjà livré) : {}", event.getPaymentId(), outcome);
            }
        } catch (RuntimeException e) {
            // Transfer refusé : claim annulé, paiement ESCROW. Le job de rattrapage ne le reprend pas
            // (il ne regarde que les PENDING) : l'alerte J+48 et le force-release restent le filet.
            log.error("Versement de rattrapage du paiement {} en échec : {}", event.getPaymentId(), e.getMessage(), e);
        }
    }

    /**
     * Verse le séquestre du paiement si son colis est déjà livré.
     *
     * @return {@link EscrowReleaseOutcome#NOT_DELIVERED} si le colis n'est pas livré (ou si le
     *         paiement n'est pas en séquestre), sinon l'issue du versement
     * @throws IllegalStateException si Stripe refuse le Transfer (claim annulé, paiement ESCROW)
     */
    public EscrowReleaseOutcome releaseIfDelivered(UUID paymentId, String source) {
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.ESCROW) {
            return EscrowReleaseOutcome.NOT_DELIVERED;
        }
        Optional<DeliveredBid> delivered = deliveredBidOf(payment);
        if (delivered.isEmpty()) {
            return EscrowReleaseOutcome.NOT_DELIVERED;
        }
        DeliveredBid bid = delivered.get();
        log.info("Paiement {} passé en séquestre après la livraison du colis {} : versement de rattrapage ({})",
                paymentId, bid.bidId(), source);
        return deliveryRelease.releaseAfterLateEscrow(bid.bidId(), bid.senderId(), bid.travelerId(), source);
    }

    /** Colis livré du paiement (par {@code bid_id}, sinon par le fil de négociation), s'il existe. */
    public Optional<DeliveredBid> deliveredBidOf(PaymentEntity payment) {
        BidEntity bid = bidOf(payment);
        if (bid == null || bid.getStatus() != BidStatus.COMPLETED) {
            return Optional.empty();
        }
        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElse(null);
        if (travelerId == null) {
            log.warn("Colis livré {} sans voyageur retrouvé : pas de versement de rattrapage", bid.getId());
            return Optional.empty();
        }
        return Optional.of(new DeliveredBid(bid.getId(), bid.getSenderId(), travelerId));
    }

    private BidEntity bidOf(PaymentEntity payment) {
        if (payment.getBidId() != null) {
            return bidRepository.findById(payment.getBidId()).orElse(null);
        }
        if (payment.getNegotiationThreadId() == null) {
            return null;
        }
        try {
            return bidRepository.findByLinkedNegotiationThreadId(payment.getNegotiationThreadId()).orElse(null);
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException ambiguous) {
            log.warn("Plusieurs colis rattachés au fil {} : pas de versement de rattrapage automatique",
                    payment.getNegotiationThreadId());
            return null;
        }
    }
}
