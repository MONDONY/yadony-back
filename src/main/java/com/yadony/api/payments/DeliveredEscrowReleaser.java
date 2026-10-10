package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
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

import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>Échec technique du versement automatique (capture refusée, Transfer refusé) : alerte
 * {@code LATE_RELEASE_FAILED_<paymentId>} (une seule tant qu'elle n'est pas résolue, close d'elle-même
 * au versement suivant réussi). La livraison ne repasse pas : sans cette alerte, rien ne retentait.
 *
 * <p>« Livré » = colis {@code COMPLETED} (code de retrait validé, {@code DeliveryConfirmedEvent}
 * publié), retrouvé par {@code bid_id} ou, pour une négociation, par le colis rattaché au fil.
 */
@Component
public class DeliveredEscrowReleaser {

    private static final Logger log = LoggerFactory.getLogger(DeliveredEscrowReleaser.class);

    /** Colis livré dont le séquestre peut être versé : identifiants nécessaires au versement. */
    public record DeliveredBid(UUID bidId, UUID senderId, UUID travelerId) {}

    /**
     * Issue d'un versement de rattrapage.
     *
     * @param failure raison lisible d'un échec technique ({@link EscrowReleaseOutcome#failed()}), sinon {@code null}
     */
    public record LateRelease(EscrowReleaseOutcome outcome, String failure) {
        static LateRelease of(EscrowReleaseOutcome outcome) {
            return new LateRelease(outcome, null);
        }
    }

    /** Préfixe (20) + UUID du paiement (36) = 56, sous la limite de 60 de {@code admin_alerts.type}. */
    public static final String FAILED_ALERT_PREFIX = "LATE_RELEASE_FAILED_";

    private final PaymentRepository paymentRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final DeliveryEventListener deliveryRelease;
    private final AdminAlertEscalator alertEscalator;

    public DeliveredEscrowReleaser(PaymentRepository paymentRepository, BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   DeliveryEventListener deliveryRelease, AdminAlertEscalator alertEscalator) {
        this.paymentRepository = paymentRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.deliveryRelease = deliveryRelease;
        this.alertEscalator = alertEscalator;
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
            LateRelease result = releaseIfDelivered(event.getPaymentId(), DeliveryEventListener.SOURCE_LATE_ESCROW);
            if (result.outcome() != EscrowReleaseOutcome.NOT_DELIVERED) {
                log.info("Séquestre tardif du paiement {} (colis déjà livré) : {}", event.getPaymentId(),
                        result.outcome());
            }
        } catch (RuntimeException e) {
            // Lecture impossible avant le versement (base indisponible) : rien n'est parti. Le job
            // d'auto-réparation reprend les séquestres livrés non versés.
            log.error("Versement de rattrapage du paiement {} impossible : {}", event.getPaymentId(), e.getMessage(), e);
        }
    }

    /**
     * Verse le séquestre du paiement si son colis est déjà livré. Un échec technique (capture ou
     * Transfer refusé) ne remonte pas : il lève l'alerte {@code LATE_RELEASE_FAILED_<paymentId>} et
     * revient en {@link EscrowReleaseOutcome#CAPTURE_FAILED} / {@link EscrowReleaseOutcome#TRANSFER_FAILED}
     * avec sa raison ; le paiement reste ESCROW (claim annulé).
     *
     * @return {@link EscrowReleaseOutcome#NOT_DELIVERED} si le colis n'est pas livré (ou si le
     *         paiement n'est pas en séquestre), sinon l'issue du versement
     */
    public LateRelease releaseIfDelivered(UUID paymentId, String source) {
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.ESCROW) {
            return LateRelease.of(EscrowReleaseOutcome.NOT_DELIVERED);
        }
        Optional<DeliveredBid> delivered = deliveredBidOf(payment);
        if (delivered.isEmpty()) {
            return LateRelease.of(EscrowReleaseOutcome.NOT_DELIVERED);
        }
        DeliveredBid bid = delivered.get();
        log.info("Paiement {} en séquestre, colis {} déjà livré : versement de rattrapage ({})",
                paymentId, bid.bidId(), source);
        EscrowReleaseOutcome outcome;
        try {
            outcome = deliveryRelease.releaseAfterLateEscrow(bid.bidId(), bid.senderId(), bid.travelerId(), source);
        } catch (RuntimeException e) {
            String reason = "versement refusé par Stripe (" + rootMessage(e) + ")";
            log.error("Versement de rattrapage du paiement {} refusé : {}", paymentId, reason, e);
            reportFailure(payment, bid.bidId(), reason, source);
            return new LateRelease(EscrowReleaseOutcome.TRANSFER_FAILED, reason);
        }
        if (outcome == EscrowReleaseOutcome.CAPTURE_FAILED) {
            String reason = "capture du séquestre impossible (voir l'alerte ESCROW_CAPTURE_FAILED)";
            reportFailure(payment, bid.bidId(), reason, source);
            return new LateRelease(outcome, reason);
        }
        if (outcome.released()) {
            resolveFailure(paymentId);
        }
        return LateRelease.of(outcome);
    }

    /**
     * Alerte {@code LATE_RELEASE_FAILED_<paymentId>} : colis livré, séquestre en place, versement
     * automatique en échec. Dédupliquée tant qu'elle n'est pas résolue.
     */
    public void reportFailure(PaymentEntity payment, UUID bidId, String reason, String source) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paymentId", payment.getId().toString());
        if (bidId != null) {
            context.put("bidId", bidId.toString());
        }
        context.put("amount", String.valueOf(payment.getAmount()));
        context.put("currency", String.valueOf(payment.getCurrency()));
        context.put("reason", reason);
        context.put("source", source);
        try {
            alertEscalator.raiseOnce(FAILED_ALERT_PREFIX + payment.getId(),
                    "Colis livré mais versement automatique impossible pour le paiement " + payment.getId() + " ("
                            + reason + ") : le paiement reste en séquestre, utilisez « Forcer le versement » après correction",
                    context);
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {}{} non levée", FAILED_ALERT_PREFIX, payment.getId(), alertFailure);
        }
    }

    private void resolveFailure(UUID paymentId) {
        try {
            alertEscalator.resolveOpen(FAILED_ALERT_PREFIX + paymentId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close après le versement : {}", FAILED_ALERT_PREFIX, paymentId, e.getMessage());
        }
    }

    static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
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
