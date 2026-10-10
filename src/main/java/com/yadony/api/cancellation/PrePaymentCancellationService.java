package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.PrePaymentCancellationResponse;
import com.yadony.api.cancellation.events.BidCancelledBeforePaymentEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * « Annuler la demande » de l'expéditeur tant que le colis attend son paiement
 * ({@code AWAITING_PAYMENT}) : vraie annulation, là où l'app ne faisait que masquer la demande.
 *
 * <p>Déroulé, dans une seule transaction :
 * <ol>
 *   <li>propriété (seul l'expéditeur) ;</li>
 *   <li>verrou du colis, relu en base (ordre colis puis paiement, comme la livraison et
 *       l'annulation admin) : un délai de paiement écoulé au même instant rend la demande déjà
 *       annulée (réponse idempotente), une promotion déjà faite répond 409 ;</li>
 *   <li>libération de l'argent par {@link PrePaymentReleasePort}, qui verrouille le paiement :
 *       PaymentIntent annulé, ou paiement mobile money en attente clos. Si l'argent est déjà
 *       autorisé ou en cours de validation, rien n'est touché et la requête répond 409 ;</li>
 *   <li>bid {@code CANCELLED} avec le motif {@value #REASON}, kilos rendus à l'annonce quand ils
 *       étaient réservés (mobile money : le voyageur avait accepté), audit, puis
 *       {@link BidCancelledBeforePaymentEvent} (voyageur prévenu, message système dans la
 *       conversation, code promo rendu, tous après commit).</li>
 * </ol>
 *
 * <p>Aucune pénalité : ni compteur d'annulation, ni réputation, rien n'a été payé. La
 * conversation n'est jamais retirée ni archivée.
 *
 * <p><b>Limite connue : ordre de verrous inverse.</b> Le webhook {@code amount_capturable_updated}
 * (puis {@code confirm-payment}) et la confirmation d'un dépôt mobile money verrouillent le
 * paiement puis le colis, l'inverse d'ici. Un croisement est un interblocage que PostgreSQL
 * détecte (après {@code deadlock_timeout}) en abandonnant l'une des deux transactions, sans état
 * incohérent : si c'est l'annulation, elle répond 409 {@code payment-in-progress} et le colis
 * apparaît payé ; si c'est le webhook, Stripe le rejoue, il trouve le paiement annulé et libère
 * l'autorisation ({@code LateAuthorizationReleaser}). Couvert par
 * {@code LateAuthorizationAfterCancellationIT}.
 */
@Service
public class PrePaymentCancellationService {

    private static final Logger log = LoggerFactory.getLogger(PrePaymentCancellationService.class);

    /** Motif posé dans {@code bids.rejection_reason}, lu par l'app pour afficher l'annulation. */
    public static final String REASON = "SENDER_CANCELLED_BEFORE_PAYMENT";

    static final String AUDIT_ACTION = "BID_CANCELLED_BEFORE_PAYMENT";

    /** Statuts où le paiement est déjà validé : l'annulation passe par le chemin avec remboursement. */
    private static final Set<BidStatus> PAID_STATUSES = EnumSet.of(
            BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED, BidStatus.HANDED_OVER,
            BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final PrePaymentReleasePort releasePort;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public PrePaymentCancellationService(BidRepository bidRepository,
                                         AnnouncementRepository announcementRepository,
                                         UserRepository userRepository,
                                         PrePaymentReleasePort releasePort,
                                         AuditService auditService,
                                         ApplicationEventPublisher eventPublisher,
                                         EntityManager entityManager) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.releasePort = releasePort;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    @Transactional
    @CacheEvict(value = {"announcements-search", "traveler-bids-me", "bids-me"}, allEntries = true)
    public PrePaymentCancellationResponse cancel(String firebaseUid, UUID bidId) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                        "unauthorized", "Unauthorized", "Utilisateur introuvable"));
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Cette demande n'existe plus."));
        if (!sender.getId().equals(bid.getSenderId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut annuler cette demande.");
        }
        // Verrou du colis D'ABORD, puis celui du paiement (dans le port) : même ordre que la
        // livraison (TrackingService.confirmDelivery) et l'annulation admin. Relu sous verrou :
        // la lecture ci-dessus a pu précéder un délai de paiement écoulé ou une promotion.
        bid = lockFresh(bid);
        if (bid.getStatus() == BidStatus.CANCELLED) {
            return alreadyCancelled(bid);
        }
        assertAwaitingPayment(bid.getStatus());

        PrePaymentReleasePort.Outcome outcome;
        try {
            outcome = releasePort.releaseBeforeCancellation(bidId, bid.getPaymentIntentId(), sender.getId());
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            // Interblocage détecté par PostgreSQL avec une confirmation de dépôt mobile money
            // (MobileMoneyBidPaymentService verrouille le paiement puis le colis) : rien n'est
            // écrit, l'expéditeur réessaie.
            log.warn("Annulation avant paiement du bid {} : verrou du paiement refusé ({})", bidId, e.getMessage());
            throw paymentInProgress();
        }
        switch (outcome) {
            case ALREADY_PAID -> throw alreadyPaid();
            case PAYMENT_IN_PROGRESS -> throw paymentInProgress();
            case RELEASED, NOTHING_TO_RELEASE -> { }
        }

        BigDecimal releasedKg = restoreCapacityIfReserved(bid);
        bid.setStatus(BidStatus.CANCELLED);
        bid.setRejectionReason(REASON);
        bid.setAwaitingPaymentExpiresAt(null);
        bidRepository.save(bid);

        Map<String, Object> payload = new HashMap<>();
        payload.put("actor", "SENDER");
        payload.put("reason", REASON);
        payload.put("paymentMethod", String.valueOf(bid.getPaymentMethod()));
        payload.put("paymentOutcome", outcome.name());
        if (releasedKg != null) {
            payload.put("releasedKg", releasedKg.toPlainString());
        }
        auditService.log("BID", bidId, AUDIT_ACTION, sender.getId(), payload);

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        eventPublisher.publishEvent(new BidCancelledBeforePaymentEvent(
                bidId, bid.getSenderId(),
                announcement != null ? announcement.getTravelerId() : null,
                bid.getAnnouncementId(),
                String.valueOf(bid.getPaymentMethod()),
                isTravelerAware(bid),
                releasedKg));
        log.info("Bid {} annulé par l'expéditeur avant paiement ({})", bidId, outcome);
        return new PrePaymentCancellationResponse(bidId, BidStatus.CANCELLED.name(), false);
    }

    /** Verrou du bid et état relu en base (pas celui, peut-être périmé, de la session). */
    private BidEntity lockFresh(BidEntity bid) {
        if (entityManager.contains(bid)) {
            entityManager.refresh(bid, LockModeType.PESSIMISTIC_WRITE);
            return bid;
        }
        return entityManager.find(BidEntity.class, bid.getId(), LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * Mobile money : la capacité a été réservée à l'acceptation du voyageur
     * ({@code MobileMoneyBidPaymentService#acceptBid}), elle lui est rendue. Carte : un bid
     * {@code AWAITING_PAYMENT} n'a jamais rien réservé (la réservation suit l'autorisation).
     *
     * @return les kilos rendus, ou {@code null}
     */
    private BigDecimal restoreCapacityIfReserved(BidEntity bid) {
        if (bid.getPaymentMethod() != PaymentMethod.MOBILE_MONEY) {
            return null;
        }
        AnnouncementEntity announcement =
                announcementRepository.findByIdForUpdate(bid.getAnnouncementId()).orElse(null);
        if (announcement == null || !announcement.releaseCapacity(bid.getWeightKg())) {
            return null;
        }
        announcementRepository.save(announcement);
        return bid.getWeightKg();
    }

    /**
     * Le voyageur connaît-il la demande ? Oui s'il l'a acceptée (mobile money) ou en a négocié
     * le prix ; non pour une réservation carte directe jamais payée, qui ne lui a jamais été
     * présentée (elle ne l'est qu'à l'autorisation du paiement).
     */
    static boolean isTravelerAware(BidEntity bid) {
        return bid.getPaymentMethod() == PaymentMethod.MOBILE_MONEY
                || bid.getNegotiationRound() > 0
                || bid.getNegotiatedGrossEur() != null;
    }

    private static void assertAwaitingPayment(BidStatus status) {
        if (status == BidStatus.AWAITING_PAYMENT) {
            return;
        }
        if (PAID_STATUSES.contains(status)) {
            throw alreadyPaid();
        }
        throw new YadonyBusinessException(HttpStatus.CONFLICT, "bid-not-awaiting-payment",
                "Bid Not Awaiting Payment", "Cette demande n'attend plus de paiement.");
    }

    private static YadonyBusinessException paymentInProgress() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "payment-in-progress", "Payment In Progress",
                "Un paiement est en cours de validation. Réessayez dans quelques minutes.");
    }

    private static YadonyBusinessException alreadyPaid() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "payment-already-authorized",
                "Payment Already Authorized",
                "Votre paiement vient d'être validé. Rouvrez le colis pour l'annuler avec remboursement.");
    }

    private static PrePaymentCancellationResponse alreadyCancelled(BidEntity bid) {
        return new PrePaymentCancellationResponse(bid.getId(), BidStatus.CANCELLED.name(), true);
    }
}
