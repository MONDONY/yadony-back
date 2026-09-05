package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserSuspendedEvent;
import com.yadony.api.cancellation.events.BidLostRematchPreparedEvent;
import com.yadony.api.cancellation.events.DeliveryNoShowReportedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.cancellation.events.ParcelReturnedEvent;
import com.yadony.api.cancellation.events.ReturnDeadlineExpiredEvent;
import com.yadony.api.cancellation.events.ReturnDeadlineWarningEvent;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.disputes.events.DisputeResolvedEvent;
import com.yadony.api.disputes.events.DisputeUpdatedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import com.yadony.api.kyc.events.UserKycActionRequiredEvent;
import com.yadony.api.matching.events.AnnouncementInProgressEvent;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.CashBidCreatedEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.HandoverAlertEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.TripArrivedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central notification orchestrator. All business services must go through this class.
 * Never call FcmService or SmsService directly from outside this package.
 *
 * Critical events (PAYMENT_RELEASED, DELIVERY_CONFIRMED, DISPUTE_OPENED) are marked
 * is_critical=true so SmsFallbackScheduler sends an SMS if no ACK arrives within 60s.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final FcmService fcmService;
    private final SmsService smsService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final BlockVisibility blockVisibility;
    private final com.yadony.api.payments.pawapay.PawapayProperties pawapayProperties;

    public NotificationDispatcher(FcmService fcmService, SmsService smsService,
                                  UserRepository userRepository,
                                  NotificationService notificationService,
                                  BlockVisibility blockVisibility,
                                  com.yadony.api.payments.pawapay.PawapayProperties pawapayProperties) {
        this.fcmService = fcmService;
        this.smsService = smsService;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.blockVisibility = blockVisibility;
        this.pawapayProperties = pawapayProperties;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void notifyUser(UUID userId, String title, String body, Map<String, String> data) {
        notifyUser(userId, title, body, data, true);
    }

    public void notifyUser(UUID userId, String title, String body, Map<String, String> data, boolean push) {
        var saved = notificationService.persist(userId, data.getOrDefault("type", ""), title, body, data, false);
        if (push) {
            Map<String, String> dataWithId = withNotificationId(data, saved.getId());
            fcmService.sendToUser(userId, title, body, dataWithId);
        }
    }

    /**
     * Notification déclenchée par un autre utilisateur : rien n'est envoyé si l'émetteur
     * est masqué pour le destinataire (blocage sans transaction en cours).
     *
     * <p>Voie distincte de {@link #notifyUser} à dessein : la voie générique sert aussi aux
     * notifications système, sans émetteur, qu'un filtre silencieux glissé dans son corps
     * aurait rendues dépendantes d'une relation qui n'existe pas. Ici l'appelant déclare
     * l'émetteur, donc la question a un sens.
     *
     * <p>Ne jamais l'utiliser pour ce qui touche à une transaction en cours (paiement,
     * remise, arrivée, litige) : {@link BlockVisibility#isHidden} les laisserait passer de
     * toute façon, mais ces notifications ne doivent dépendre d'aucune règle de blocage.
     *
     * @return vrai si la notification a été émise, faux si elle a été supprimée
     */
    public boolean notifyUnlessBlocked(UUID recipientId, UUID actorId, String title, String body,
                                       Map<String, String> data) {
        return notifyUnlessBlocked(recipientId, actorId, title, body, data, true);
    }

    /** Variante de {@link #notifyUnlessBlocked} qui contrôle l'envoi du push. */
    public boolean notifyUnlessBlocked(UUID recipientId, UUID actorId, String title, String body,
                                       Map<String, String> data, boolean push) {
        if (blockVisibility.isHidden(recipientId, actorId)) {
            log.debug("Notification supprimée : émetteur {} masqué pour {}", actorId, recipientId);
            return false;
        }
        notifyUser(recipientId, title, body, data, push);
        return true;
    }

    // Critical: persisted with is_critical=true → SmsFallbackScheduler sends SMS if no ACK in 60s
    private void notifyCritical(UUID userId, String title, String body, Map<String, String> data) {
        var saved = notificationService.persist(userId, data.getOrDefault("type", ""), title, body, data, true);
        Map<String, String> dataWithId = withNotificationId(data, saved.getId());
        fcmService.sendToUser(userId, title, body, dataWithId);
    }

    private static Map<String, String> withNotificationId(Map<String, String> original, UUID id) {
        var copy = new HashMap<>(original);
        copy.put("notificationId", id.toString());
        return copy;
    }

    public void notifyBySms(String phoneNumber, String message) {
        smsService.send(phoneNumber, message);
    }

    // ── Story 8.2 — Event listeners ──────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidCreated(BidCreatedEvent event) {
        notifyNewBid(event.getBidId(), event.getAnnouncementId(), event.getTravelerId(),
                event.getSenderId(), event.getSenderFirstName(), event.getWeightKg(),
                event.getCorridor());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCashBidCreated(CashBidCreatedEvent event) {
        notifyNewBid(event.bidId(), event.announcementId(), event.travelerId(),
                event.senderId(), event.senderFirstName(), event.weightKg(), event.corridor());
    }

    private void notifyNewBid(UUID bidId, UUID announcementId, UUID travelerId, UUID senderId,
                              String senderFirstName, BigDecimal weightKg, String corridor) {
        var text = NotificationTexts.newBid(senderFirstName, weightKg, corridor);
        // Une nouvelle demande est déclenchée par l'expéditeur : rien ne part si le
        // voyageur et lui sont masqués l'un pour l'autre.
        notifyUnlessBlocked(travelerId, senderId, text.title(), text.body(),
                Map.of("type", "BID_CREATED",
                       "bidId", bidId.toString(),
                       "announcementId", announcementId.toString()));
    }

    public void onHandoverAlert(HandoverAlertEvent event) {
        var text = NotificationTexts.handoverReminder(event.handoverLocation());
        notifyCritical(event.senderId(), text.title(), text.body(),
                Map.of("type", "HANDOVER_REMINDER_H2",
                       "bidId", event.bidId().toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onUserKycVerified(UserKycVerifiedEvent event) {
        var text = NotificationTexts.kycVerified();
        notifyUser(event.getUserId(), text.title(), text.body(), Map.of("type", "KYC_VERIFIED"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onUserKycActionRequired(UserKycActionRequiredEvent event) {
        var text = NotificationTexts.kycActionRequired();
        notifyUser(event.userId(), text.title(), text.body(), Map.of("type", "KYC_ACTION_REQUIRED"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onParcelReturned(ParcelReturnedEvent event) {
        Map<String, String> data = Map.of(
                "type", "PARCEL_RETURNED", "bidId", event.bidId().toString());
        var forSender = NotificationTexts.parcelReturnedForSender();
        var forTraveler = NotificationTexts.parcelReturnedForTraveler();
        notifyUser(event.senderId(), forSender.title(), forSender.body(), data);
        notifyUser(event.travelerId(), forTraveler.title(), forTraveler.body(), data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnDeadlineWarning(ReturnDeadlineWarningEvent event) {
        Map<String, String> data = Map.of(
                "type", "RETURN_DEADLINE_WARNING", "bidId", event.bidId().toString());
        var forSender = NotificationTexts.returnDeadlineWarningForSender();
        var forTraveler = NotificationTexts.returnDeadlineWarningForTraveler();
        notifyUser(event.senderId(), forSender.title(), forSender.body(), data);
        notifyUser(event.travelerId(), forTraveler.title(), forTraveler.body(), data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnDeadlineExpired(ReturnDeadlineExpiredEvent event) {
        Map<String, String> data = Map.of(
                "type", "RETURN_DEADLINE_EXPIRED", "bidId", event.bidId().toString());
        var text = NotificationTexts.returnDeadlineExpired();
        if (event.senderId() != null) {
            notifyUser(event.senderId(), text.title(), text.body(), data);
        }
        if (event.travelerId() != null) {
            notifyUser(event.travelerId(), text.title(), text.body(), data);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidAccepted(BidAcceptedEvent event) {
        if (event.isMobileMoney()) {
            // Le paiement suit dans l'application : ce push remplace « Demande acceptée ! »
            // et ouvre l'écran d'attente. Persisté ET poussé. Le délai vient de la
            // configuration (jamais en dur : "sous 30 min" mentirait dès que
            // yadony.pawapay.deposit-deadline-minutes changerait, sans qu'aucun test ne
            // le remarque).
            var pay = NotificationTexts.mobileMoneyPaymentPending(pawapayProperties.depositDeadlineMinutes());
            notifyUser(event.getSenderId(), pay.title(), pay.body(),
                    Map.of("type", "MM_PAYMENT_PENDING", "bidId", event.getBidId().toString()));
            return;
        }
        // publicDisplayName : source unique du nom d'affichage ; le catalogue le réduit
        // ensuite à « Prénom I. » pour tenir dans le corps, et reste générique sans nom.
        String name = userRepository.findById(event.getTravelerId())
                .map(com.yadony.api.auth.UserEntity::publicDisplayName)
                .orElse(null);
        var text = NotificationTexts.bidAccepted(name);
        notifyUnlessBlocked(event.getSenderId(), event.getTravelerId(), text.title(), text.body(),
                Map.of("type", "BID_ACCEPTED", "bidId", event.getBidId().toString()), true);
    }

    @EventListener @Async
    public void onBidRejected(BidRejectedEvent event) {
        if (event.isRematchEligible()) return; // relayé par onBidLostRematchPrepared (X2/X3)
        // Lot B (revue round 3) : le motif technique ANNOUNCEMENT_DELETED (posé par
        // AnnouncementService#removeByAdmin, rematchEligible=false car décision de
        // modération) n'est PAS un refus du voyageur — le libellé générique « Demande
        // refusée » accusait à tort un voyageur qui n'avait rien fait, et ne mentionnait
        // jamais que l'expéditeur allait être remboursé.
        if (BidEntity.REJECTION_ANNOUNCEMENT_DELETED.equals(event.getReason())) {
            var withdrawn = NotificationTexts.bidRejectedTripWithdrawn();
            notifyUser(event.getSenderId(), withdrawn.title(), withdrawn.body(),
                    Map.of("type", "BID_REJECTED", "bidId", event.getBidId().toString()));
            return;
        }
        var rejected = NotificationTexts.bidRejected();
        notifyUser(event.getSenderId(), rejected.title(), rejected.body(),
                Map.of("type", "BID_REJECTED", "bidId", event.getBidId().toString()));
    }

    // Notification unique (BID_REJECTED conservé) pour un bid perdu par annulation/refus voyageur,
    // avec deep link rematch si des suggestions existent. Même pattern AFTER_COMMIT + @Async que
    // onTripCancelled : le deep link cancellationId ne doit jamais partir avant que
    // BidLostRematchListener (cancellation/) ait commité la CancellationEntity + les suggestions.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidLostRematchPrepared(BidLostRematchPreparedEvent event) {
        // Lot B (revue round 3) : au sein des motifs « initiés par le voyageur »
        // (cancelledByTraveler=true), une suppression de trajet (deleteAnnouncement) n'est
        // pas une annulation de transport — libellé dédié plutôt que le texte générique.
        NotificationTexts.BidLoss loss;
        if (BidEntity.REJECTION_ANNOUNCEMENT_DELETED.equals(event.reason())) {
            loss = NotificationTexts.BidLoss.TRIP_DELETED;
        } else if (event.cancelledByTraveler()) {
            loss = NotificationTexts.BidLoss.TRANSPORT_CANCELLED;
        } else {
            loss = NotificationTexts.BidLoss.REFUSED;
        }
        int n = event.suggestionCount();
        // Défense : count > 0 avec cancellationId null ne devrait pas arriver (contrat X2 garantit
        // cancellationId non-null dès que suggestionCount > 0), mais si ça survient on retombe
        // sur le corps "remboursement en cours" sans deep link plutôt que de risquer un NPE.
        if (n > 0 && event.cancellationId() != null) {
            var text = NotificationTexts.bidLostWithRematch(loss, n);
            notifyUser(event.senderId(), text.title(), text.body(),
                    Map.of("type", "BID_REJECTED",
                           "bidId", event.bidId().toString(),
                           "cancellationId", event.cancellationId().toString()));
        } else {
            var text = NotificationTexts.bidLostRefund(loss);
            notifyUser(event.senderId(), text.title(), text.body(),
                    Map.of("type", "BID_REJECTED", "bidId", event.bidId().toString()));
        }
    }

    // Le deep link cancellationId ne doit pas partir avant le commit de cancelTrip
    // (rollback → push mensonger ; race → 404 sur GET /cancellations/{id}/rematch-suggestions).
    // Pattern reproduit de TripCancelledEventListener (payments) : AFTER_COMMIT + @Async, sans
    // @Transactional(REQUIRES_NEW) — ce listener ne fait que lire/notifier, pas de refund à isoler.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTripCancelled(TripCancelledEvent event) {
        if (event.getAffectedSenderIds() == null) return;
        for (UUID senderId : event.getAffectedSenderIds()) {
            TripCancelledEvent.RematchBySenderInfo info = event.getRematchBySender().get(senderId);
            if (info == null) {
                var text = NotificationTexts.tripCancelledRefund();
                notifyUser(senderId, text.title(), text.body(), Map.of("type", "TRIP_CANCELLED"));
            } else if (info.suggestionCount() > 0) {
                var text = NotificationTexts.tripCancelledWithRematch(info.suggestionCount());
                notifyUser(senderId, text.title(), text.body(),
                        Map.of("type", "TRIP_CANCELLED",
                               "cancellationId", info.cancellationId().toString()));
            } else {
                var text = NotificationTexts.tripCancelledNoTraveler();
                notifyUser(senderId, text.title(), text.body(), Map.of("type", "TRIP_CANCELLED"));
            }
        }
    }

    @EventListener @Async
    public void onDeliveryNoShowReported(DeliveryNoShowReportedEvent event) {
        Map<String, String> data = Map.of("type", "DELIVERY_NOSHOW_REPORTED", "bidId", event.getBidId().toString());
        if (event.isReportedByTraveler()) {
            var text = NotificationTexts.deliveryNoShowForSender();
            notifyUser(event.getSenderId(), text.title(), text.body(), data);
        } else {
            var text = NotificationTexts.deliveryNoShowForTraveler();
            notifyUser(event.getTravelerId(), text.title(), text.body(), data);
        }
    }

    // Critical events — SMS fallback triggered by SmsFallbackScheduler after 60s without ACK

    @EventListener @Async
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        var text = NotificationTexts.deliveryConfirmed();
        notifyCritical(event.getSenderId(), text.title(), text.body(),
                Map.of("type", "DELIVERY_CONFIRMED", "bidId", event.getBidId().toString()));
    }

    // Trajet arrivé à destination — notif expéditeur par colis (instructions de retrait)
    @EventListener @Async
    public void onTripArrived(TripArrivedEvent event) {
        Map<String, String> data = Map.of("type", "TRIP_ARRIVED",
                "announcementId", event.getAnnouncementId().toString());
        var text = NotificationTexts.tripArrived();
        for (TripArrivedEvent.BidTarget target : event.getTargets()) {
            notifyUser(target.senderId(), text.title(), text.body(), data);
        }
    }

    @EventListener @Async
    public void onPaymentReleased(PaymentReleasedEvent event) {
        var text = NotificationTexts.paymentReleased(NotificationTexts.eur(event.getAmount()));
        notifyCritical(event.getTravelerId(), text.title(), text.body(),
                Map.of("type", "PAYMENT_RELEASED", "bidId", event.getBidId().toString()));
    }

    @EventListener @Async
    public void onDisputeOpened(DisputeOpenedEvent event) {
        Map<String, String> data = Map.of("type", "DISPUTE_OPENED", "bidId", event.getBidId().toString());
        var forSender = NotificationTexts.disputeOpenedForSender();
        var forTraveler = NotificationTexts.disputeOpenedForTraveler();
        notifyCritical(event.getSenderId(), forSender.title(), forSender.body(), data);
        notifyCritical(event.getTravelerId(), forTraveler.title(), forTraveler.body(), data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDisputeUpdated(DisputeUpdatedEvent event) {
        Map<String, String> data = disputeData(
                "DISPUTE_UPDATED", event.disputeId(), event.bidId());
        var text = NotificationTexts.disputeUpdated();
        notifyDisputeParties(event.senderId(), event.travelerId(), text.title(), text.body(), data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDisputeResolved(DisputeResolvedEvent event) {
        Map<String, String> data = disputeData(
                "DISPUTE_RESOLVED", event.disputeId(), event.bidId());
        var text = NotificationTexts.disputeResolved();
        notifyDisputeParties(event.senderId(), event.travelerId(), text.title(), text.body(), data);
    }

    private Map<String, String> disputeData(String type, UUID disputeId, UUID bidId) {
        Map<String, String> data = new HashMap<>();
        data.put("type", type);
        data.put("disputeId", disputeId.toString());
        if (bidId != null) data.put("bidId", bidId.toString());
        return data;
    }

    private void notifyDisputeParties(UUID senderId, UUID travelerId,
                                      String title, String body, Map<String, String> data) {
        if (senderId != null) notifyUser(senderId, title, body, data);
        if (travelerId != null) notifyUser(travelerId, title, body, data);
    }

    // Story 9.4 — Notification expéditeur : colis refusé
    @EventListener @Async
    public void onParcelRefused(ParcelRefusedEvent event) {
        var text = NotificationTexts.parcelRefused(event.getReason());
        notifyUser(event.getSenderId(), text.title(), text.body(),
                Map.of("type", "PARCEL_REFUSED", "bidId", event.getBidId().toString()));
    }

    // Story 9.6 — Notification expéditeur : voyageur no-show
    @EventListener @Async
    public void onVoyageurNoShow(VoyageurNoShowEvent event) {
        var text = NotificationTexts.travelerNoShow();
        notifyUser(event.getSenderId(), text.title(), text.body(),
                Map.of("type", "TRIP_CANCELLED", "bidId", event.getBidId().toString()));
    }

    // Trajet en cours — notif voyageur "Bon voyage"
    //
    // In-app seulement : aucune action n'est demandée et rien n'est annoncé que le voyageur
    // ignore — il sait qu'il part, c'est lui qui a saisi la date. Le rappel de scanner les QR
    // codes garde son utilité dans la boîte de réception, mais ne justifie pas d'interrompre.
    @EventListener @Async
    public void onAnnouncementInProgress(AnnouncementInProgressEvent event) {
        var text = NotificationTexts.tripInProgress();
        notifyUser(event.getTravelerId(), text.title(), text.body(),
                Map.of("type", "TRIP_IN_PROGRESS",
                       "announcementId", event.getAnnouncementId().toString()),
                false);
    }

    // Bid expiré au départ — notif expéditeur "Demande expirée"
    @EventListener @Async
    public void onBidExpiredOnDeparture(BidExpiredOnDepartureEvent event) {
        var text = NotificationTexts.bidExpired();
        notifyUser(event.getSenderId(), text.title(), text.body(),
                Map.of("type", "BID_EXPIRED",
                       "bidId", event.getBidId().toString()));
    }

    // Story 9.5 — Notification utilisateur : compte suspendu
    @EventListener @Async
    public void onUserSuspended(UserSuspendedEvent event) {
        var text = NotificationTexts.accountSuspended();
        notifyUser(event.getUserId(), text.title(), text.body(), Map.of("type", "ACCOUNT_SUSPENDED"));
    }

    // Messaging — new message notification (called by MessagingNotifyController)
    //
    // Renvoie l'UID Firebase du destinataire, ou null si l'expéditeur est inconnu.
    // La Cloud Function s'en sert pour créditer le compteur de non-lus quand elle
    // n'a pas pu déterminer le destinataire elle-même : la base est la source de
    // vérité des participants, le document Firestore n'en est qu'un reflet, et il
    // peut manquer.
    public String sendMessageNotification(UUID senderId, UUID travelerId,
                                          String senderFirebaseUid, String preview,
                                          String conversationId) {
        var senderUser = userRepository.findByFirebaseUid(senderFirebaseUid).orElse(null);

        if (senderUser == null) {
            log.warn("sendMessageNotification: unknown senderFirebaseUid={}", senderFirebaseUid);
            return null;
        }

        // publicDisplayName() : « Un utilisateur » ne permettait pas de savoir qui écrit
        // quand l'expéditeur du message n'a pas renseigné de prénom.
        String senderName = senderUser.publicDisplayName();
        UUID recipientId = senderUser.getId().equals(senderId) ? travelerId : senderId;

        // Fil masqué : ni push, ni UID renvoyé. La Cloud Function se sert de cet UID pour
        // créditer le compteur de non-lus ; le renvoyer ferait apparaître un badge pour un
        // message que le destinataire n'est pas censé voir.
        if (blockVisibility.isHidden(recipientId, senderUser.getId())) {
            log.debug("Notification supprimée : émetteur {} masqué pour {}", senderUser.getId(), recipientId);
            return null;
        }

        // Push seul, rien en base : la messagerie porte déjà son badge et sa liste,
        // une ligne de plus dans le feed ferait deux endroits à vider pour un même
        // message (refonte du sheet, 2026-09). L'aperçu est coupé au mot, jamais
        // au milieu, à la longueur que deux lignes tiennent.
        String truncated = NotificationCaps.truncateAtWord(preview, NotificationCaps.BODY_MAX);
        fcmService.sendToUser(recipientId, "Message de " + NotificationCaps.shortDisplayName(senderName), truncated,
                Map.of("type", "NEW_MESSAGE", "conversationId", conversationId));

        return userRepository.findById(recipientId)
                .map(com.yadony.api.auth.UserEntity::getFirebaseUid)
                .orElse(null);
    }

    public void sendCardExpiringNotice(com.yadony.api.auth.UserEntity user) {
        var text = NotificationTexts.cardExpiring(user.getCommissionCardBrand(), user.getCommissionCardLast4());
        notifyUser(user.getId(), text.title(), text.body(), Map.of("type", "CARD_EXPIRING"));
    }
}
