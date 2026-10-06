package com.yadony.api.matching.reception;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.events.ArrivalInstructionsUpdatedEvent;
import com.yadony.api.matching.events.BidRecipientChangedEvent;
import com.yadony.api.matching.events.RecipientReplacementRequestedEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.TripArrivedEvent;
import com.yadony.api.matching.events.TripRescheduledEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationText;
import com.yadony.api.notifications.NotificationTexts;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Tient au courant le destinataire qui suit son colis dans l'app (lien CONFIRMED) :
 * départ (code de retrait disponible), arrivée, remise, report du trajet. Push FCM
 * uniquement, jamais de SMS. Un destinataire sans lien confirmé ne reçoit rien, sauf
 * l'annulation : elle prévient aussi le lien PENDING, déjà averti du colis à venir.
 */
@Component
public class ReceptionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ReceptionNotificationListener.class);

    /** Le lien PENDING a reçu « Un colis pour vous ? » : il doit savoir que l'envoi tombe. */
    private static final Set<ReceptionLinkStatus> CANCELLATION_AUDIENCE =
            EnumSet.of(ReceptionLinkStatus.PENDING, ReceptionLinkStatus.CONFIRMED);

    /** À l'arrivée, le lien PENDING est prévenu aussi : au tap, l'app lui fait confirmer. */
    private static final Set<ReceptionLinkStatus> ARRIVAL_AUDIENCE =
            EnumSet.of(ReceptionLinkStatus.PENDING, ReceptionLinkStatus.CONFIRMED);

    private final BidRecipientLinkRepository linkRepository;
    private final AnnouncementRepository announcementRepository;
    private final NotificationDispatcher notificationDispatcher;

    public ReceptionNotificationListener(BidRecipientLinkRepository linkRepository,
                                         AnnouncementRepository announcementRepository,
                                         NotificationDispatcher notificationDispatcher) {
        this.linkRepository = linkRepository;
        this.announcementRepository = announcementRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onParcelDeparted(ParcelDepartedEvent event) {
        notifyConfirmedRecipient(event.bidId(), ReceptionNotifications.DEPARTED,
                m -> NotificationTexts.recipientParcelDeparted(m));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTripArrived(TripArrivedEvent event) {
        String arrivalCity = announcementRepository.findById(event.getAnnouncementId())
                .map(a -> a.getArrivalCity())
                .orElse(null);
        for (TripArrivedEvent.BidTarget target : event.getTargets()) {
            notifyRecipient(target.bidId(), ReceptionNotifications.ARRIVED, ARRIVAL_AUDIENCE,
                    m -> NotificationTexts.recipientParcelArrived(m, arrivalCity));
        }
    }

    /** Instructions de retrait modifiées après l'arrivée : seul le lien CONFIRMED les lit. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onArrivalInstructionsUpdated(ArrivalInstructionsUpdatedEvent event) {
        if (event.bidIds() == null) {
            return;
        }
        for (UUID bidId : event.bidIds()) {
            notifyConfirmedRecipient(bidId, ReceptionNotifications.PICKUP_UPDATED,
                    m -> NotificationTexts.recipientPickupUpdated(m));
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        notifyConfirmedRecipient(event.getBidId(), ReceptionNotifications.DELIVERED,
                m -> NotificationTexts.recipientParcelDelivered(m));
    }

    /**
     * Trajet annulé par le voyageur, annulation après remise, retrait de l'expéditeur
     * après un report : une cible par colis dans {@code affectedBidIds}.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTripCancelled(TripCancelledEvent event) {
        List<UUID> bidIds = event.getAffectedBidIds();
        if (bidIds == null) {
            return;
        }
        for (UUID bidId : bidIds) {
            notifyCancelled(bidId);
        }
    }

    /**
     * Annulation d'un colis seul : l'expéditeur ou le voyageur annule avant la remise,
     * refus, retrait du trajet par la modération, compte de l'expéditeur supprimé. Une
     * demande sans lien (jamais acceptée) ne prévient personne.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidRejected(BidRejectedEvent event) {
        notifyCancelled(event.getBidId());
    }

    /** Absence de l'expéditeur à la remise confirmée : le colis ne partira pas. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        notifyCancelled(event.bidId());
    }

    /**
     * Les nouvelles dates s'appliquent dès le report : le colis reste sur le trajet tant
     * que l'expéditeur ne se retire pas (s'il se tait, il reste). Un retrait arrive
     * ensuite par {@link TripCancelledEvent}.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTripRescheduled(TripRescheduledEvent event) {
        if (event.targets() == null) {
            return;
        }
        for (TripRescheduledEvent.Target target : event.targets()) {
            notifyConfirmedRecipient(target.bidId(), ReceptionNotifications.RESCHEDULED,
                    m -> NotificationTexts.recipientParcelRescheduled(m));
        }
    }

    /**
     * L'expéditeur a changé de destinataire : l'ancien titulaire du lien (PENDING ou
     * CONFIRMED) apprend que le colis n'est plus pour lui, le voyageur qu'il doit
     * remettre le colis à quelqu'un d'autre. Chaque envoi est indépendant.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidRecipientChanged(BidRecipientChangedEvent event) {
        UUID bidId = event.bidId();
        if (bidId == null) {
            return;
        }
        send(event.previousRecipientUserId(), bidId, ReceptionNotifications.REASSIGNED,
                NotificationTexts::recipientParcelReassigned);
        send(event.travelerId(), bidId, ReceptionNotifications.RECIPIENT_CHANGED,
                NotificationTexts::recipientChanged);
    }

    /** Le voyageur demande à l'expéditeur de désigner un autre destinataire. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecipientReplacementRequested(RecipientReplacementRequestedEvent event) {
        if (event.bidId() == null) {
            return;
        }
        send(event.senderId(), event.bidId(), ReceptionNotifications.REPLACEMENT_REQUESTED,
                NotificationTexts::recipientReplacementRequested);
    }

    private void send(UUID userId, UUID bidId, String type,
                      Function<com.yadony.api.common.i18n.Messages, NotificationText> text) {
        if (userId == null) {
            return;
        }
        try {
            NotificationText t = text.apply(notificationDispatcher.messagesFor(userId));
            notificationDispatcher.notifyUser(userId, t.title(), t.body(),
                    Map.of("type", type, "bidId", bidId.toString()));
        } catch (Exception e) {
            log.warn("Notification {} du colis {} impossible : {}", type, bidId, e.toString());
        }
    }

    private void notifyCancelled(UUID bidId) {
        notifyRecipient(bidId, ReceptionNotifications.CANCELLED, CANCELLATION_AUDIENCE,
                m -> NotificationTexts.recipientParcelCancelled(m));
    }

    private void notifyConfirmedRecipient(UUID bidId, String type,
                                          Function<com.yadony.api.common.i18n.Messages, NotificationText> text) {
        notifyRecipient(bidId, type, EnumSet.of(ReceptionLinkStatus.CONFIRMED), text);
    }

    private void notifyRecipient(UUID bidId, String type, Set<ReceptionLinkStatus> audience,
                                 Function<com.yadony.api.common.i18n.Messages, NotificationText> text) {
        if (bidId == null) {
            return;
        }
        try {
            linkRepository.findByBidId(bidId)
                    .filter(l -> audience.contains(l.getStatus()))
                    .ifPresent(link -> {
                        UUID recipientId = link.getRecipientUserId();
                        NotificationText t = text.apply(notificationDispatcher.messagesFor(recipientId));
                        notificationDispatcher.notifyUser(recipientId, t.title(), t.body(),
                                Map.of("type", type, "bidId", bidId.toString()));
                    });
        } catch (Exception e) {
            log.warn("Notification {} au destinataire du colis {} impossible : {}", type, bidId, e.toString());
        }
    }
}
