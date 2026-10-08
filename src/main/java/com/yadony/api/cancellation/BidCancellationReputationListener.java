package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.TravelerHighCancellationEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidCancelledByParticipantEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Fiabilité sur annulation d'un colis à l'unité ({@code BidService.cancelBid}),
 * FLUTTER-E4/E0/E6.
 *
 * <ul>
 *   <li>Le voyageur annule un colis qu'il avait accepté → +1 sur
 *       {@code cancellationCount}, comme une annulation de trajet (même audit, même
 *       alerte à 3).</li>
 *   <li>L'expéditeur annule un colis déjà accepté par le voyageur → +1 sur
 *       {@code senderCancellationCount}.</li>
 * </ul>
 * Une annulation avant toute acceptation (demande en attente, en négociation, payée par
 * carte mais pas encore acceptée) ne compte pour personne.
 *
 * <p>Pas de double compte : l'annulation d'un trajet entier n'appelle pas cancelBid et ne
 * publie pas cet événement, elle compte une seule annulation quel que soit le nombre de
 * colis. AFTER_COMMIT + REQUIRES_NEW, comme {@link SenderReputationListener} : la
 * réputation n'est écrite que si l'annulation a bien eu lieu.
 */
@Component
public class BidCancellationReputationListener {

    private static final Logger log = LoggerFactory.getLogger(BidCancellationReputationListener.class);

    /** Seuil d'alerte admin, aligné sur {@code CancellationService.cancelTrip}. */
    static final int HIGH_CANCELLATION_THRESHOLD = 3;

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public BidCancellationReputationListener(UserRepository userRepository,
                                             AuditService auditService,
                                             ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Le voyageur avait-il accepté le colis au moment de l'annulation ? ACCEPTED, remis
     * (HANDED_OVER, encore annulable avant le départ), ou mobile money en attente du
     * paiement de l'expéditeur : la place est alors déjà réservée par l'acceptation.
     * PAYMENT_ESCROWED (carte payée, pas encore acceptée) et AWAITING_PAYMENT carte
     * (paiement avant acceptation) n'en sont pas.
     */
    static boolean wasAcceptedByTraveler(BidStatus previousStatus, PaymentMethod paymentMethod) {
        if (previousStatus == null) {
            return false;
        }
        return switch (previousStatus) {
            case ACCEPTED, HANDED_OVER -> true;
            case AWAITING_PAYMENT -> paymentMethod == PaymentMethod.MOBILE_MONEY;
            default -> false;
        };
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidCancelled(BidCancelledByParticipantEvent event) {
        if (!wasAcceptedByTraveler(event.previousStatus(), event.paymentMethod())) {
            return;
        }
        UserEntity actor = userRepository.findById(event.actorId()).orElse(null);
        if (actor == null) {
            log.debug("Fiabilité : auteur {} de l'annulation du bid {} introuvable",
                    event.actorId(), event.bidId());
            return;
        }
        if (event.byTraveler()) {
            countTravelerCancellation(actor, event);
        } else {
            countSenderCancellation(actor, event);
        }
    }

    private void countTravelerCancellation(UserEntity traveler, BidCancelledByParticipantEvent event) {
        int count = traveler.getCancellationCount() + 1;
        traveler.setCancellationCount(count);
        userRepository.save(traveler);

        auditService.log("BID", event.bidId(), "BID_CANCELLED_BY_TRAVELER_COUNTED", traveler.getId(),
                Map.of("previousStatus", event.previousStatus().name(),
                       "cancellationCount", String.valueOf(count)));

        if (count >= HIGH_CANCELLATION_THRESHOLD) {
            auditService.log("USER", traveler.getId(), "HIGH_CANCELLATION_ALERT", traveler.getId(),
                    Map.of("cancellationCount", String.valueOf(count),
                           "triggeringBidId", event.bidId().toString()));
            eventPublisher.publishEvent(new TravelerHighCancellationEvent(
                    traveler.getId(), count, event.announcementId()));
        }
        log.info("Fiabilité voyageur {} : annulation du colis {} comptée (count={})",
                traveler.getId(), event.bidId(), count);
    }

    private void countSenderCancellation(UserEntity sender, BidCancelledByParticipantEvent event) {
        int count = sender.getSenderCancellationCount() + 1;
        sender.setSenderCancellationCount(count);
        userRepository.save(sender);

        auditService.log("USER", sender.getId(), "SENDER_CANCELLATION_COUNTED", sender.getId(),
                Map.of("bidId", event.bidId().toString(),
                       "previousStatus", event.previousStatus().name(),
                       "count", count));
        log.info("Fiabilité expéditeur {} : annulation du colis {} après acceptation (count={})",
                sender.getId(), event.bidId(), count);
    }
}
