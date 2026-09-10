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
        // Nouvelle offre déclenchée par le voyageur : supprimée si les deux comptes sont
        // masqués l'un pour l'autre.
        var text = NotificationTexts.negotiationStarted(e.proposedPriceEur());
        dispatcher.notifyUnlessBlocked(
            e.senderId(),
            e.travelerId(),
            text.title(),
            text.body(),
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
        // Contre-proposition postée par l'autre partie : même règle que l'offre initiale.
        var text = NotificationTexts.negotiationCounter(e.newPriceEur(), e.roundsCount());
        dispatcher.notifyUnlessBlocked(
            e.toUserId(),
            e.fromUserId(),
            text.title(),
            text.body(),
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
        var text = NotificationTexts.negotiationAwaitingTrip(e.agreedPriceEur());
        dispatcher.notifyUser(
            e.travelerId(),
            text.title(),
            text.body(),
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
        var text = NotificationTexts.negotiationAwaitingPayment(e.agreedPriceEur());
        dispatcher.notifyUser(
            e.senderId(),
            text.title(),
            text.body(),
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
        var text = NotificationTexts.negotiationTripChanged();
        dispatcher.notifyUser(
            e.senderId(),
            text.title(),
            text.body(),
            Map.of(
                "type", "negotiation_trip_changed",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Accord en espèces conclu par l'expéditeur : le voyageur doit régler la
     * commission pour l'emporter. AFTER_COMMIT, car annoncer un accord avant son
     * commit exposerait à notifier une transaction qui rollback ensuite.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationCommissionPending(NegotiationCommissionPendingEvent e) {
        var text = NotificationTexts.commissionPending(e.commissionAmount(), e.currency());
        dispatcher.notifyUser(
            e.travelerId(),
            text.title(),
            text.body(),
            Map.of(
                "type", "negotiation_commission_pending",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Le voyageur a renoncé à l'accord en espèces avant de régler la commission.
     * Rien n'était scellé — notifie l'expéditeur que sa demande reste disponible.
     *
     * <p>{@code AFTER_COMMIT} : même raison que {@link #onNegotiationCancelled} —
     * aucun push « le voyageur a renoncé » ne doit partir si la transaction du
     * renoncement finit par rollback.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationCommissionDeclined(NegotiationCommissionDeclinedEvent e) {
        var text = NotificationTexts.commissionDeclined();
        dispatcher.notifyUser(
            e.senderId(),
            text.title(),
            text.body(),
            Map.of(
                "type", "negotiation_commission_declined",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Dépôt mobile money lancé sur un fil (offre acceptée, en attente de règlement) :
     * l'expéditeur doit payer, le voyageur est simplement informé. {@code AFTER_COMMIT} :
     * même raison que {@link #onNegotiationCommissionPending} — pas de push avant que le
     * dépôt initié ne soit acquis en base.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationDepositPending(NegotiationDepositPendingEvent e) {
        var data = Map.of(
                "type", "negotiation_deposit_pending",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
        );
        var forSender = NotificationTexts.depositPendingSender(e.gross(), e.currency());
        dispatcher.notifyUser(e.senderId(), forSender.title(), forSender.body(), data);
        var forTraveler = NotificationTexts.depositPendingTraveler();
        dispatcher.notifyUser(e.travelerId(), forTraveler.title(), forTraveler.body(), data);
    }

    /**
     * Le dépôt mobile money n'a pas abouti (échec pawaPay, échéance passée ou
     * renoncement de l'expéditeur) : le fil revient à « à payer », seul l'expéditeur
     * est notifié — l'accord tient, rien ne change pour le voyageur.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationDepositReverted(NegotiationDepositRevertedEvent e) {
        if (e.senderId() == null) return;
        var text = NotificationTexts.depositReverted(e.reason());
        dispatcher.notifyUser(
                e.senderId(),
                text.title(),
                text.body(),
                Map.of(
                        "type", "negotiation_deposit_reverted",
                        "threadId", e.threadId().toString(),
                        "packageRequestId", e.packageRequestId().toString()
                )
        );
    }

    /**
     * Le voyageur n'a pas réglé la commission dans le délai imparti. Rien n'était
     * scellé — les deux parties sont notifiées, chacune avec son propre message :
     * le voyageur a perdu la demande, l'expéditeur peut de nouveau la conclure.
     * In-app seulement, même logique que {@link #onNegotiationExpired} : c'est une
     * absence d'action, pas un événement qui appelle une réaction immédiate.
     *
     * <p>{@code AFTER_COMMIT} : l'expiration entre en concurrence avec le règlement
     * du voyageur qui paie juste à l'échéance. Une expiration qui échoue au commit
     * (le règlement a gagné) ne doit pas avoir déjà annoncé « cette demande n'est
     * plus disponible pour vous » à un voyageur dont l'accord est scellé en base.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onNegotiationCommissionExpired(NegotiationCommissionExpiredEvent e) {
        var forTraveler = NotificationTexts.commissionExpiredForTraveler();
        dispatcher.notifyUser(
            e.travelerId(),
            forTraveler.title(),
            forTraveler.body(),
            Map.of(
                "type", "negotiation_commission_expired",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            ),
            false
        );
        // senderId est nul quand l'expéditeur a supprimé sa demande pendant
        // l'attente : il n'y a alors plus personne à prévenir, et notifier null
        // violerait la contrainte NOT NULL de notifications.user_id. Même garde
        // que onNegotiationExpired.
        if (e.senderId() != null) {
            var forSender = NotificationTexts.commissionExpiredForSender();
            dispatcher.notifyUser(
                e.senderId(),
                forSender.title(),
                forSender.body(),
                Map.of(
                    "type", "negotiation_commission_expired",
                    "threadId", e.threadId().toString(),
                    "packageRequestId", e.packageRequestId().toString()
                ),
                false
            );
        }
    }

    /**
     * Final ACCEPTED state — fires only after payment is captured (escrow active).
     * Notify both parties that the deal is sealed.
     */
    @EventListener
    @Async
    public void onPackageRequestAccepted(PackageRequestAcceptedEvent e) {
        var forTraveler = NotificationTexts.requestAcceptedForTraveler(e.agreedPriceEur());
        dispatcher.notifyUser(
            e.travelerId(),
            forTraveler.title(),
            forTraveler.body(),
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
        var forSender = NotificationTexts.requestAcceptedForSender(e.agreedPriceEur());
        dispatcher.notifyUser(
            e.senderId(),
            forSender.title(),
            forSender.body(),
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
        var text = NotificationTexts.requestExpired();
        dispatcher.notifyUser(
            e.senderId(),
            text.title(),
            text.body(),
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
        // Relance envoyée à la main par l'autre partie : c'est exactement le type de
        // sollicitation qu'un blocage doit faire taire.
        var text = NotificationTexts.negotiationReminder(e.fromUserName());
        dispatcher.notifyUnlessBlocked(
            e.toUserId(),
            e.fromUserId(),
            text.title(),
            text.body(),
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
        var text = NotificationTexts.negotiationEnded(e.byName());
        dispatcher.notifyUser(
            e.toUserId(),
            text.title(),
            text.body(),
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
        var text = NotificationTexts.negotiationExpired();
        dispatcher.notifyUser(
            e.travelerId(),
            text.title(),
            text.body(),
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
                text.title(),
                text.body(),
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
