package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidHandoverDeadlinePassedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Expiration d'UNE demande à la date limite de dépôt (FLUTTER-GA), dans sa propre
 * transaction ({@code REQUIRES_NEW}) et sous verrou : un conflit saute la demande sans
 * annuler le lot, et une acceptation concurrente est sérialisée.
 *
 * <p>Statut et date limite sont relus et revérifiés ici, jamais crus sur la foi du
 * balayage : un trajet reporté entre-temps (nouvelle date limite) ou une demande acceptée
 * juste avant ne sont pas touchés. Deux passages successifs ne produisent qu'une transition.
 *
 * <p>Les fils de négociation ne passent pas par ici mais par
 * {@link BidNegotiationExpiryRunner}, qui porte déjà leur extinction.
 */
@Component
public class HandoverDeadlineExpiryRunner {

    /** Ce que le passage a fait, pour que le scheduler sache s'il doit vider les caches. */
    public enum Outcome { EXPIRED, PAYMENT_WINDOW_CLOSED, IGNORED }

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public HandoverDeadlineExpiryRunner(BidRepository bidRepository,
                                        AnnouncementRepository announcementRepository,
                                        ApplicationEventPublisher eventPublisher,
                                        AuditService auditService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome expire(UUID bidId, Instant now) {
        BidEntity bid = bidRepository.findByIdForUpdate(bidId).orElse(null);
        if (bid == null) {
            return Outcome.IGNORED;
        }
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        if (announcement == null || !announcement.isHandoverDeadlinePassed(now)) {
            return Outcome.IGNORED;
        }
        return switch (bid.getStatus()) {
            case PENDING, PAYMENT_ESCROWED -> expireBid(bid, announcement);
            case AWAITING_PAYMENT -> closePaymentWindow(bid, announcement, now);
            default -> Outcome.IGNORED;
        };
    }

    /**
     * Demande en attente de réponse du voyageur : EXPIRED, et remboursement intégral de tout
     * paiement (annulation du hold ou remboursement) par le chemin de l'expiration au départ,
     * {@code BidExpiredOnDepartureEvent} → {@code RefundProcessor}. Aucune capacité à rendre :
     * PENDING et PAYMENT_ESCROWED n'en réservent pas (elle n'est prise qu'à l'acceptation).
     */
    private Outcome expireBid(BidEntity bid, AnnouncementEntity announcement) {
        BidStatus previous = bid.getStatus();
        bid.setStatus(BidStatus.EXPIRED);
        bid.setRejectionReason(HandoverDeadlineRules.EXPIRY_REASON);
        bidRepository.save(bid);

        auditService.log("BID", bid.getId(), "BID_EXPIRED_HANDOVER_DEADLINE", null,
                auditPayload(bid, announcement, previous));

        eventPublisher.publishEvent(new BidExpiredOnDepartureEvent(
                bid.getId(), bid.getSenderId(), announcement.getId(), announcement.getTravelerId(),
                HandoverDeadlineRules.EXPIRY_REASON));
        eventPublisher.publishEvent(new BidHandoverDeadlinePassedEvent(
                bid.getId(), announcement.getId(), bid.getSenderId(), announcement.getTravelerId(),
                previous == BidStatus.PAYMENT_ESCROWED, true));
        return Outcome.EXPIRED;
    }

    /**
     * Demande en attente de paiement : l'échéance de paiement est ramenée à maintenant, et ce
     * sont les expirations EXISTANTES qui annulent au passage suivant — elles seules savent
     * trancher les courses avec un paiement en vol :
     * <ul>
     *   <li>carte : {@code AwaitingPaymentCleanupScheduler} annule le PaymentIntent, ou promeut
     *       le bid en PAYMENT_ESCROWED si Stripe l'a autorisé entre-temps — il est alors éteint
     *       au passage suivant d'ici, avec remboursement intégral ;</li>
     *   <li>mobile money : {@code MobileMoneyPaymentDeadlineScheduler} attend un dépôt encore
     *       ouvert, rend la capacité réservée à l'acceptation et prévient les deux parties.</li>
     * </ul>
     */
    private Outcome closePaymentWindow(BidEntity bid, AnnouncementEntity announcement, Instant now) {
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalDateTime current = bid.getAwaitingPaymentExpiresAt();
        if (current != null && !current.isAfter(nowUtc)) {
            return Outcome.IGNORED; // déjà échue : l'expiration existante passera
        }
        bid.setAwaitingPaymentExpiresAt(nowUtc);
        bidRepository.save(bid);

        auditService.log("BID", bid.getId(), "BID_PAYMENT_WINDOW_CLOSED_HANDOVER_DEADLINE", null,
                auditPayload(bid, announcement, BidStatus.AWAITING_PAYMENT));

        // Mobile money : l'expiration existante prévient déjà les deux parties ; une seconde
        // notification ferait doublon. Carte : l'abandon est silencieux, on prévient ici —
        // le voyageur seulement s'il connaissait la demande (accord négocié).
        if (bid.getPaymentMethod() != PaymentMethod.MOBILE_MONEY) {
            eventPublisher.publishEvent(new BidHandoverDeadlinePassedEvent(
                    bid.getId(), announcement.getId(), bid.getSenderId(), announcement.getTravelerId(),
                    false, bid.getNegotiatedGrossEur() != null));
        }
        return Outcome.PAYMENT_WINDOW_CLOSED;
    }

    private static Map<String, Object> auditPayload(BidEntity bid, AnnouncementEntity announcement,
                                                    BidStatus previous) {
        return Map.of(
                "source", "SYSTEM",
                "reason", HandoverDeadlineRules.EXPIRY_REASON,
                "previousStatus", previous.name(),
                "announcementId", announcement.getId().toString(),
                "handoverDeadline", String.valueOf(announcement.getHandoverDeadline()),
                "paymentMethod", String.valueOf(bid.getPaymentMethod()));
    }
}
