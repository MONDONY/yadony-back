package com.yadony.api.notifications;

import com.yadony.api.requests.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
public class RequestEventsListener {

    private static final Logger log = LoggerFactory.getLogger(RequestEventsListener.class);

    private final NotificationDispatcher dispatcher;

    public RequestEventsListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @EventListener
    @Async
    public void onPackageRequestCreated(PackageRequestCreatedEvent e) {
        // La notif temps réel « un colis matche un de mes trajets » est gérée par
        // PackageMatchTravelerNotifyListener (package matching). Ici, trace audit uniquement.
        log.info("PackageRequestCreated event: requestId={} corridor={}->{}",
            e.requestId(), e.departureCity(), e.arrivalCity());
    }

    @EventListener
    @Async
    public void onNegotiationStarted(NegotiationStartedEvent e) {
        dispatcher.notifyUser(
            e.senderId(),
            "Nouvelle proposition reçue",
            String.format("Un voyageur propose %.2f€ pour votre demande", e.proposedPriceEur()),
            Map.of(
                "type", "negotiation_started",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    @EventListener
    @Async
    public void onNegotiationCounterPosted(NegotiationCounterPostedEvent e) {
        dispatcher.notifyUser(
            e.toUserId(),
            "Nouvelle contre-proposition",
            String.format("Nouvelle offre: %.2f€ (round %d)", e.newPriceEur(), e.roundsCount()),
            Map.of(
                "type", "negotiation_counter",
                "threadId", e.threadId().toString(),
                "messageId", e.messageId().toString()
            )
        );
    }

    /**
     * Sender just clicked "Accepter le prix". Status is now AWAITING_TRIP.
     * Notify the traveler that they need to link a trip.
     */
    @EventListener
    @Async
    public void onNegotiationAwaitingTrip(NegotiationAwaitingTripEvent e) {
        dispatcher.notifyUser(
            e.travelerId(),
            "Votre offre a été acceptée 🎉",
            String.format("L'expéditeur a accepté à %.0f€. Choisissez maintenant le trajet à lier.",
                e.agreedPriceEur()),
            Map.of(
                "type", "negotiation_awaiting_trip",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Traveler just linked a trip. Status is now AWAITING_PAYMENT.
     * Notify the sender that they need to pay.
     */
    @EventListener
    @Async
    public void onNegotiationAwaitingPayment(NegotiationAwaitingPaymentEvent e) {
        dispatcher.notifyUser(
            e.senderId(),
            "Trajet validé — paiement requis 💳",
            String.format("Le voyageur a confirmé son trajet. Payez %.0f€ pour finaliser la commande.",
                e.agreedPriceEur()),
            Map.of(
                "type", "negotiation_awaiting_payment",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Traveler changed the linked trip before payment. Notify the sender —
     * they may want to review the new trip details before accepting/paying.
     */
    @EventListener
    @Async
    public void onNegotiationTripChanged(com.yadony.api.requests.event.NegotiationTripChangedEvent e) {
        dispatcher.notifyUser(
            e.senderId(),
            "Trajet mis à jour",
            "Le voyageur a changé le trajet associé à votre demande.",
            Map.of(
                "type", "negotiation_trip_changed",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Règlement en espèces bloqué : la commission n'a pas pu être prélevée au
     * voyageur. Lui seul peut débloquer l'accord, en rechargeant son portefeuille.
     * Publié depuis une transaction qui rollback, d'où le {@code @EventListener}
     * simple (un AFTER_COMMIT ne partirait jamais).
     */
    @EventListener
    @Async
    public void onNegotiationCashCommissionFailed(NegotiationCashCommissionFailedEvent e) {
        dispatcher.notifyUser(
            e.travelerId(),
            "Rechargez votre portefeuille",
            String.format(
                "L'expéditeur règle en espèces : la commission de %.2f %s n'a pas pu être prélevée. "
                    + "Rechargez votre portefeuille pour valider l'accord.",
                e.commissionAmount(), e.currency()),
            Map.of(
                "type", "negotiation_cash_commission_failed",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Final ACCEPTED state — fires only after payment is captured (escrow active).
     * Notify both parties that the deal is sealed.
     */
    @EventListener
    @Async
    public void onPackageRequestAccepted(PackageRequestAcceptedEvent e) {
        dispatcher.notifyUser(
            e.travelerId(),
            "Paiement reçu — c'est parti ✈️",
            String.format("Le paiement de %.0f€ est en escrow. Vous pouvez préparer le retrait du colis.",
                e.agreedPriceEur()),
            Map.of(
                "type", "request_accepted",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
        // Côté expéditeur : in-app seulement. Cet événement suit immédiatement SON paiement,
        // qu'il vient de confirmer dans l'application — l'écran de succès le lui a déjà dit.
        // Le voyageur, lui, garde son push : c'est une nouvelle pour lui, et elle appelle une
        // action (préparer le retrait du colis).
        dispatcher.notifyUser(
            e.senderId(),
            "Demande finalisée ✅",
            String.format("Paiement de %.0f€ confirmé. Le voyageur va vous contacter.",
                e.agreedPriceEur()),
            Map.of(
                "type", "request_accepted",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            ),
            false
        );
    }

    @EventListener
    @Async
    public void onPackageRequestExpired(PackageRequestExpiredEvent e) {
        dispatcher.notifyUser(
            e.senderId(),
            "Votre demande a expiré",
            "Aucun voyageur n'a accepté votre demande dans les délais. Vous pouvez en créer une nouvelle.",
            Map.of(
                "type", "request_expired",
                "packageRequestId", e.requestId().toString()
            )
        );
    }

    /**
     * The waiting party nudged the other one to remind them to respond.
     */
    @EventListener
    @Async
    public void onNegotiationNudgeSent(NegotiationNudgeSentEvent e) {
        dispatcher.notifyUser(
            e.toUserId(),
            "Relance",
            e.fromUserName() + " attend de vos nouvelles sur votre négociation.",
            Map.of(
                "type", "negotiation",
                "threadId", e.threadId().toString()
            )
        );
    }

    /**
     * A participant ended the negotiation before payment. Notify the other party.
     *
     * <p>{@code AFTER_COMMIT} (not a plain {@code @EventListener}) so no spurious
     * "négociation terminée" push is sent if the cancel transaction rolls back
     * (e.g. a CHECK-constraint failure or a concurrent finalize winning the race).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationCancelled(NegotiationCancelledEvent e) {
        dispatcher.notifyUser(
            e.toUserId(),
            "Négociation terminée",
            e.byName() + " a mis fin à la négociation.",
            Map.of(
                "type", "negotiation",
                "threadId", e.threadId().toString()
            )
        );
    }

    /**
     * In-app seulement des deux côtés : une expiration est l'absence d'événement, pas un
     * événement. Personne n'a rien fait pendant des jours, et il n'y a rien à faire une fois
     * le délai écoulé — réveiller deux téléphones pour annoncer que rien ne s'est passé est
     * le pire rapport valeur/interruption de tout le catalogue.
     */
    @EventListener
    @Async
    public void onNegotiationExpired(NegotiationExpiredEvent e) {
        // Notify traveler
        dispatcher.notifyUser(
            e.travelerId(),
            "Négociation expirée",
            "Cette négociation a expiré faute d'activité.",
            Map.of(
                "type", "negotiation_expired",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            ),
            false
        );
        // senderId may be null — only notify if present
        // (event currently passes null for senderId per scheduler; can be enriched later)
        if (e.senderId() != null) {
            dispatcher.notifyUser(
                e.senderId(),
                "Négociation expirée",
                "Cette négociation a expiré faute d'activité.",
                Map.of(
                    "type", "negotiation_expired",
                    "threadId", e.threadId().toString(),
                    "packageRequestId", e.packageRequestId().toString()
                ),
                false
            );
        }
    }
}
