package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserSuspendedEvent;
import com.yadony.api.cancellation.events.BidLostRematchPreparedEvent;
import com.yadony.api.cancellation.events.DeliveryNoShowReportedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.cancellation.events.ParcelReturnedEvent;
import com.yadony.api.cancellation.events.ReturnDeadlineExpiredEvent;
import com.yadony.api.cancellation.events.ReturnDeadlineWarningEvent;
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
import com.yadony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
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
    private static final int MESSAGE_PREVIEW_MAX_LENGTH = 60;

    private final FcmService fcmService;
    private final SmsService smsService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public NotificationDispatcher(FcmService fcmService, SmsService smsService,
                                  UserRepository userRepository,
                                  NotificationService notificationService) {
        this.fcmService = fcmService;
        this.smsService = smsService;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
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
                event.getSenderFirstName(), event.getWeightKg(), event.getCorridor());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCashBidCreated(CashBidCreatedEvent event) {
        notifyNewBid(event.bidId(), event.announcementId(), event.travelerId(),
                event.senderFirstName(), event.weightKg(), event.corridor());
    }

    private void notifyNewBid(UUID bidId, UUID announcementId, UUID travelerId,
                              String senderFirstName, BigDecimal weightKg, String corridor) {
        String body = weightKg != null
                ? String.format("%s veut envoyer %.1f kg — %s",
                        senderFirstName, weightKg.doubleValue(), corridor)
                : String.format("%s a une demande d'envoi — %s",
                        senderFirstName, corridor);
        notifyUser(travelerId, "Nouvelle demande d'envoi", body,
                Map.of("type", "BID_CREATED",
                       "bidId", bidId.toString(),
                       "announcementId", announcementId.toString()));
    }

    public void onHandoverAlert(HandoverAlertEvent event) {
        String body = "Dernier créneau pour déposer votre colis à " + event.handoverLocation()
                + " — confirmation du voyageur en attente.";
        notifyCritical(event.senderId(), "Plus que 2 heures pour déposer", body,
                Map.of("type", "HANDOVER_REMINDER_H2",
                       "bidId", event.bidId().toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onUserKycVerified(UserKycVerifiedEvent event) {
        notifyUser(event.getUserId(), "Identité vérifiée",
                "Votre identité est vérifiée. Vous pouvez maintenant publier et effectuer vos transactions.",
                Map.of("type", "KYC_VERIFIED"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onUserKycActionRequired(UserKycActionRequiredEvent event) {
        notifyUser(event.userId(), "Vérification à compléter",
                "Une action est nécessaire pour terminer la vérification de votre identité.",
                Map.of("type", "KYC_ACTION_REQUIRED"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidPaidByMobileMoney(BidPaidByMobileMoneyEvent event) {
        notifyUser(event.getTravelerId(), "Paiement confirmé",
                "Le paiement Mobile Money de cet envoi est confirmé.",
                Map.of("type", "MOBILE_MONEY_PAYMENT_CONFIRMED",
                       "bidId", event.getBidId().toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onParcelReturned(ParcelReturnedEvent event) {
        Map<String, String> data = Map.of(
                "type", "PARCEL_RETURNED", "bidId", event.bidId().toString());
        notifyUser(event.senderId(), "Colis rendu",
                "Le retour de votre colis a été confirmé.", data);
        notifyUser(event.travelerId(), "Retour confirmé",
                "La restitution du colis est enregistrée.", data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnDeadlineWarning(ReturnDeadlineWarningEvent event) {
        Map<String, String> data = Map.of(
                "type", "RETURN_DEADLINE_WARNING", "bidId", event.bidId().toString());
        notifyUser(event.senderId(), "Communiquez votre code de retour",
                "Le délai de retour expire dans moins de 24 heures.", data);
        notifyUser(event.travelerId(), "Retour du colis à effectuer",
                "Confirmez la restitution avant l'expiration du délai.", data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnDeadlineExpired(ReturnDeadlineExpiredEvent event) {
        Map<String, String> data = Map.of(
                "type", "RETURN_DEADLINE_EXPIRED", "bidId", event.bidId().toString());
        if (event.senderId() != null) {
            notifyUser(event.senderId(), "Délai de retour dépassé",
                    "Le retour du colis n'a pas été confirmé. Notre équipe a été alertée.", data);
        }
        if (event.travelerId() != null) {
            notifyUser(event.travelerId(), "Délai de retour dépassé",
                    "Le retour du colis n'a pas été confirmé. Notre équipe a été alertée.", data);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidAccepted(BidAcceptedEvent event) {
        String name = userRepository.findById(event.getTravelerId())
                .map(u -> u.getFirstName() != null ? u.getFirstName() : "Le voyageur")
                .orElse("Le voyageur");
        // Paiement par lien externe : MobileMoneyBidAcceptedListener envoie « Payez votre
        // trajet », qui annonce déjà l'acceptation ET porte le lien de paiement. Pousser en
        // plus « Demande acceptée ! » ferait deux push pour la même action, le second
        // répétant le premier. On persiste quand même la trace pour la boîte de réception.
        notifyUser(event.getSenderId(), "Demande acceptée !",
                name + " accepte votre colis",
                Map.of("type", "BID_ACCEPTED", "bidId", event.getBidId().toString()),
                !event.isMobileMoney());
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
            notifyUser(event.getSenderId(), "Trajet retiré",
                    "Ce trajet n'est plus disponible — votre remboursement est en cours",
                    Map.of("type", "BID_REJECTED", "bidId", event.getBidId().toString()));
            return;
        }
        notifyUser(event.getSenderId(), "Demande refusée",
                "Le voyageur a refusé votre demande",
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
        String title;
        String prefix;
        if (BidEntity.REJECTION_ANNOUNCEMENT_DELETED.equals(event.reason())) {
            title = "Trajet supprimé";
            prefix = "Le voyageur a supprimé son trajet";
        } else if (event.cancelledByTraveler()) {
            title = "Transport annulé";
            prefix = "Le voyageur a annulé le transport de votre colis";
        } else {
            title = "Demande refusée";
            prefix = "Le voyageur a refusé votre demande";
        }
        int n = event.suggestionCount();
        // Défense : count > 0 avec cancellationId null ne devrait pas arriver (contrat X2 garantit
        // cancellationId non-null dès que suggestionCount > 0), mais si ça survient on retombe
        // sur le corps "remboursement en cours" sans deep link plutôt que de risquer un NPE.
        if (n > 0 && event.cancellationId() != null) {
            notifyUser(event.senderId(), title,
                    prefix + " — remboursement en cours. " + n
                            + " voyageur" + (n > 1 ? "s" : "") + " alternatif" + (n > 1 ? "s" : "")
                            + " disponible" + (n > 1 ? "s" : ""),
                    Map.of("type", "BID_REJECTED",
                           "bidId", event.bidId().toString(),
                           "cancellationId", event.cancellationId().toString()));
        } else {
            notifyUser(event.senderId(), title,
                    prefix + " — votre remboursement est en cours",
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
                notifyUser(senderId, "Trajet annulé",
                        "Le voyageur a annulé son trajet. Remboursement en cours.",
                        Map.of("type", "TRIP_CANCELLED"));
            } else if (info.suggestionCount() > 0) {
                int n = info.suggestionCount();
                notifyUser(senderId, "Trajet annulé",
                        "Trajet annulé — remboursement en cours. "
                                + n + " voyageur" + (n > 1 ? "s" : "") + " alternatif"
                                + (n > 1 ? "s" : "") + " disponible" + (n > 1 ? "s" : ""),
                        Map.of("type", "TRIP_CANCELLED",
                               "cancellationId", info.cancellationId().toString()));
            } else {
                notifyUser(senderId, "Trajet annulé",
                        "Trajet annulé — Aucun voyageur disponible dans les 72h, votre remboursement est traité",
                        Map.of("type", "TRIP_CANCELLED"));
            }
        }
    }

    @EventListener @Async
    public void onDeliveryNoShowReported(DeliveryNoShowReportedEvent event) {
        Map<String, String> data = Map.of("type", "DELIVERY_NOSHOW_REPORTED", "bidId", event.getBidId().toString());
        if (event.isReportedByTraveler()) {
            notifyUser(event.getSenderId(), "Absence signalée à la livraison",
                    "Le voyageur signale que votre destinataire ne s'est pas présenté", data);
        } else {
            notifyUser(event.getTravelerId(), "Absence signalée à la livraison",
                    "L'expéditeur signale que vous n'avez pas livré le colis", data);
        }
    }

    // Critical events — SMS fallback triggered by SmsFallbackScheduler after 60s without ACK

    @EventListener @Async
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        notifyCritical(event.getSenderId(), "Livraison confirmée",
                "Votre colis est arrivé à destination",
                Map.of("type", "DELIVERY_CONFIRMED", "bidId", event.getBidId().toString()));
    }

    // Trajet arrivé à destination — notif expéditeur par colis (instructions de retrait)
    @EventListener @Async
    public void onTripArrived(TripArrivedEvent event) {
        Map<String, String> data = Map.of("type", "TRIP_ARRIVED",
                "announcementId", event.getAnnouncementId().toString());
        for (TripArrivedEvent.BidTarget target : event.getTargets()) {
            notifyUser(target.senderId(), "Votre voyageur est arrivé",
                    "Instructions de retrait disponibles dans le suivi de votre colis", data);
        }
    }

    @EventListener @Async
    public void onPaymentReleased(PaymentReleasedEvent event) {
        String amount = String.format(java.util.Locale.FRENCH, "%.2f €", event.getAmount().doubleValue());
        notifyCritical(event.getTravelerId(), "Paiement reçu !",
                amount + " — virement en cours, sous 24h",
                Map.of("type", "PAYMENT_RELEASED", "bidId", event.getBidId().toString()));
    }

    @EventListener @Async
    public void onDisputeOpened(DisputeOpenedEvent event) {
        Map<String, String> data = Map.of("type", "DISPUTE_OPENED", "bidId", event.getBidId().toString());
        notifyCritical(event.getSenderId(),  "Litige ouvert", "Un incident a été signalé sur votre envoi",  data);
        notifyCritical(event.getTravelerId(), "Litige ouvert", "Un incident a été signalé sur votre colis", data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDisputeUpdated(DisputeUpdatedEvent event) {
        Map<String, String> data = disputeData(
                "DISPUTE_UPDATED", event.disputeId(), event.bidId());
        notifyDisputeParties(event.senderId(), event.travelerId(),
                "Litige mis à jour",
                "Une nouvelle décision financière a été enregistrée sur votre litige.", data);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDisputeResolved(DisputeResolvedEvent event) {
        Map<String, String> data = disputeData(
                "DISPUTE_RESOLVED", event.disputeId(), event.bidId());
        notifyDisputeParties(event.senderId(), event.travelerId(),
                "Litige résolu",
                "Une décision finale a été prise. Consultez le détail du litige.", data);
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
        String reason = event.getReason() != null ? event.getReason() : "contenu non conforme";
        notifyUser(event.getSenderId(), "Colis refusé",
                "Votre colis a été refusé par le voyageur — raison : " + reason,
                Map.of("type", "PARCEL_REFUSED", "bidId", event.getBidId().toString()));
    }

    // Story 9.6 — Notification expéditeur : voyageur no-show
    @EventListener @Async
    public void onVoyageurNoShow(VoyageurNoShowEvent event) {
        notifyUser(event.getSenderId(), "Voyageur absent",
                "Le voyageur ne s'est pas présenté à la remise. Remboursement en cours.",
                Map.of("type", "TRIP_CANCELLED", "bidId", event.getBidId().toString()));
    }

    // Trajet en cours — notif voyageur "Bon voyage"
    //
    // In-app seulement : aucune action n'est demandée et rien n'est annoncé que le voyageur
    // ignore — il sait qu'il part, c'est lui qui a saisi la date. Le rappel de scanner les QR
    // codes garde son utilité dans la boîte de réception, mais ne justifie pas d'interrompre.
    @EventListener @Async
    public void onAnnouncementInProgress(AnnouncementInProgressEvent event) {
        notifyUser(event.getTravelerId(), "Bon voyage !",
                "N'oublie pas de scanner les QR codes à la remise et à la livraison.",
                Map.of("type", "TRIP_IN_PROGRESS",
                       "announcementId", event.getAnnouncementId().toString()),
                false);
    }

    // Bid expiré au départ — notif expéditeur "Demande expirée"
    @EventListener @Async
    public void onBidExpiredOnDeparture(BidExpiredOnDepartureEvent event) {
        notifyUser(event.getSenderId(), "Demande expirée",
                "Le voyageur est parti avant d'avoir accepté votre demande. Remboursement en cours.",
                Map.of("type", "BID_EXPIRED",
                       "bidId", event.getBidId().toString()));
    }

    // Story 9.5 — Notification utilisateur : compte suspendu
    @EventListener @Async
    public void onUserSuspended(UserSuspendedEvent event) {
        notifyUser(event.getUserId(), "Compte suspendu",
                "Votre compte a été suspendu suite à des incidents répétés",
                Map.of("type", "ACCOUNT_SUSPENDED"));
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

        String truncated = preview.length() > MESSAGE_PREVIEW_MAX_LENGTH
                ? preview.substring(0, MESSAGE_PREVIEW_MAX_LENGTH - 3) + "..."
                : preview;
        notifyUser(recipientId, "Message de " + senderName, truncated,
                Map.of("type", "NEW_MESSAGE", "conversationId", conversationId));

        return userRepository.findById(recipientId)
                .map(com.yadony.api.auth.UserEntity::getFirebaseUid)
                .orElse(null);
    }

    public void sendCardExpiringNotice(com.yadony.api.auth.UserEntity user) {
        String brand = user.getCommissionCardBrand() != null ? user.getCommissionCardBrand() : "Carte";
        String last4 = user.getCommissionCardLast4() != null ? user.getCommissionCardLast4() : "****";
        notifyUser(user.getId(),
                "Votre carte de débit expire bientôt",
                brand + " se terminant par " + last4 + " expire ce mois-ci. " +
                "Mettez-la à jour pour continuer à accepter des paiements cash.",
                Map.of("type", "CARD_EXPIRING"));
    }
}
