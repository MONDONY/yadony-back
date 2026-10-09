package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class NotificationPrefsService {

    /**
     * Type de notification → interrupteur qui le gouverne.
     *
     * <p>Un type absent de cette table et de {@link #ALWAYS_ON} part toujours : c'est le
     * défaut sûr pour une notification nouvelle, mais il rend un réglage inopérant sans
     * bruit (FLUTTER-GB). {@code NotificationPrefsClassificationTest} échoue donc dès qu'un
     * type émis dans le code n'est classé nulle part.
     */
    static final Map<String, String> TYPE_TO_PREF = Map.ofEntries(
            Map.entry("BID_CREATED",                  "pushActivityBids"),
            Map.entry("BID_ACCEPTED",                 "pushActivityBids"),
            Map.entry("BID_REJECTED",                 "pushActivityBids"),
            Map.entry("PARCEL_REFUSED",               "pushActivityBids"),
            Map.entry("BID_EXPIRED",                  "pushActivityBids"),
            Map.entry("TRIP_CANCELLED",               "pushActivityBids"),
            Map.entry("TRIP_RESCHEDULE_KEPT",         "pushActivityBids"),
            Map.entry("TRIP_RESCHEDULE_WITHDRAWN",    "pushActivityBids"),
            Map.entry("negotiation_started",          "pushActivityNegotiations"),
            Map.entry("negotiation_counter",          "pushActivityNegotiations"),
            Map.entry("negotiation_awaiting_trip",    "pushActivityNegotiations"),
            Map.entry("negotiation_awaiting_payment", "pushActivityNegotiations"),
            Map.entry("request_accepted",             "pushActivityNegotiations"),
            Map.entry("request_expired",              "pushActivityNegotiations"),
            Map.entry("negotiation_expired",          "pushActivityNegotiations"),
            // Relance et « négociation terminée » partagent ce type générique. Sans cette
            // entrée, elles échappaient à toute préférence : isAllowed renvoie true par
            // défaut pour un type inconnu.
            Map.entry("negotiation",                  "pushActivityNegotiations"),
            // FLUTTER-GB : le reste de la négociation (trajet modifié, commission, dépôt,
            // discussion de prix sur une offre) échappait à l'interrupteur.
            Map.entry("negotiation_trip_changed",        "pushActivityNegotiations"),
            Map.entry("negotiation_commission_pending",  "pushActivityNegotiations"),
            Map.entry("negotiation_commission_declined", "pushActivityNegotiations"),
            Map.entry("negotiation_commission_expired",  "pushActivityNegotiations"),
            Map.entry("negotiation_deposit_pending",     "pushActivityNegotiations"),
            Map.entry("negotiation_deposit_reverted",    "pushActivityNegotiations"),
            Map.entry("bid_negotiation_message",         "pushActivityNegotiations"),
            Map.entry("bid_negotiation_expired",         "pushActivityNegotiations"),
            // Famille « quelqu'un répond à mon colis » : ces trois-là appellent une action de
            // l'expéditeur et suivent donc le même interrupteur que les offres reçues.
            Map.entry("TRAVELER_INVITE",              "pushActivityBids"),
            Map.entry("CONFIRMATION_CODE_READY",      "pushActivityBids"),
            Map.entry("CONFIRMATION_CODE_BLOCKED",    "pushActivityBids"),
            Map.entry("CONFIRMATION_CODE_REQUESTED",  "pushActivityBids"),
            Map.entry("DELIVERY_NOSHOW_REPORTED",     "pushActivityBids"),
            Map.entry("DELIVERY_RETRY_APPOINTMENT",   "pushActivityBids"),
            Map.entry("PARCEL_UNCLAIMED",             "pushActivityBids"),
            Map.entry("MM_PAYMENT_PENDING",           "pushActivityBids"),
            Map.entry("MM_PAYMENT_EXPIRED",           "pushActivityBids"),
            Map.entry("MOBILE_MONEY_PAYMENT_CONFIRMED", "pushActivityBids"),
            Map.entry("MOBILE_MONEY_PAYMENT_FAILED",  "pushActivityBids"),
            Map.entry("PARCEL_RETURNED",              "pushActivityBids"),
            Map.entry("PARCEL_RETURN_REQUIRED",       "pushActivityBids"),
            Map.entry("PARCEL_RETURN_TO_SENDER",      "pushActivityBids"),
            Map.entry("RETURN_DEADLINE_WARNING",      "pushActivityBids"),
            Map.entry("RETURN_DEADLINE_EXPIRED",      "pushActivityBids"),
            // Suivi du colis par son destinataire (lot 2) et réponses relayées à l'expéditeur.
            Map.entry("RECIPIENT_PARCEL_INCOMING",    "pushActivityBids"),
            Map.entry("RECIPIENT_PARCEL_ANNOUNCED",   "pushActivityBids"),
            Map.entry("RECIPIENT_PARCEL_DEPARTED",    "pushActivityBids"),
            Map.entry("RECIPIENT_PARCEL_ARRIVED",     "pushActivityBids"),
            Map.entry("RECIPIENT_PARCEL_DELIVERED",   "pushActivityBids"),
            Map.entry("RECIPIENT_PARCEL_CANCELLED",   "pushActivityBids"),
            Map.entry("RECIPIENT_PARCEL_RESCHEDULED", "pushActivityBids"),
            Map.entry("RECIPIENT_PICKUP_UPDATED",     "pushActivityBids"),
            Map.entry("RECIPIENT_CONFIRMED",          "pushActivityBids"),
            Map.entry("RECIPIENT_DECLINED",           "pushActivityBids"),
            Map.entry("RECIPIENT_WITHDRAWN",          "pushActivityBids"),
            Map.entry("RECIPIENT_REPLACEMENT_REQUESTED", "pushActivityBids"),
            // Changement de destinataire (lot 3A) : ancien destinataire et voyageur.
            Map.entry("RECIPIENT_PARCEL_REASSIGNED",  "pushActivityBids"),
            Map.entry("RECIPIENT_CHANGED",            "pushActivityBids"),
            // Invitations au carnet de destinataires (lot 4).
            Map.entry("RECIPIENT_INVITATION",         "pushActivityBids"),
            Map.entry("RECIPIENT_INVITATION_ACCEPTED", "pushActivityBids"),
            Map.entry("RECIPIENT_INVITATION_REMOVED", "pushActivityBids"),
            // FLUTTER-GB : arrivée du trajet (instructions de retrait, à l'expéditeur) et
            // demande de colis retirée par la modération, suivies comme le reste du colis.
            Map.entry("TRIP_ARRIVED",                 "pushActivityBids"),
            Map.entry("PACKAGE_REQUEST_REMOVED",      "pushActivityBids"),
            Map.entry("NEW_MESSAGE",                  "pushMessages"),
            // Le support vit dans l'onglet Messages (« Yadony Support ») : même interrupteur.
            // Seul le push est coupé, l'entrée du centre de notifications reste.
            Map.entry("SUPPORT_MESSAGE",              "pushMessages"),
            // « Bon voyage ! » suivait pushTripReminder, dont l'interrupteur a quitté
            // l'application : qui l'avait coupé ne pouvait plus le rallumer. Le rattacher à
            // un réglage visible plutôt que forcer la colonne à true par migration : une
            // ancienne version de l'app renvoie la valeur coupée de son cache à chaque PUT
            // et aurait défait la migration. pushTripReminder n'est plus lu par aucun type.
            Map.entry("TRIP_IN_PROGRESS",             "pushRemindersTips"),
            Map.entry("FIRST_ACTION_REMINDER",        "pushRemindersTips"),
            Map.entry("CALL_MISSED",                  "pushMissedCalls"),
            Map.entry("automation_capacity_free",     "pushTravelerAutomations"),
            Map.entry("automation_loyal_sender",      "pushTravelerAutomations"),
            Map.entry("automation_last_minute",       "pushTravelerAutomations"),
            Map.entry("PROMO",                        "pushPromo"),
            Map.entry("CORRIDOR_ALERT",               "pushCorridorAlerts"),
            // Même famille que les alertes corridor du point de vue de l'utilisateur :
            // « on me signale un nouveau trajet ». L'abonnement voyageur garde en plus son
            // propre interrupteur par abonnement ; celui-ci est le garde-fou global, pour
            // qui coupe la découverte de trajets sans vouloir dénouer chaque abonnement.
            Map.entry("TRAVELER_NEW_ANNOUNCEMENT",    "pushCorridorAlerts"),
            // PackageMatchTravelerNotifyListener coupe déjà en amont via
            // isPackageMatchEnabled ; cette entrée aligne isAllowed sur le même
            // interrupteur pour que tout futur émetteur du type soit filtré sans
            // avoir à répliquer le garde-fou du listener.
            Map.entry("PACKAGE_MATCH",                "pushTripPackageMatch"),
            // Un expéditeur propose son colis au voyageur : même famille que les colis compatibles.
            Map.entry("SENDER_INVITE",                "pushTripPackageMatch")
    );

    /**
     * Types volontairement non réglables : ils partent quels que soient les réglages.
     * Argent et portefeuille, vérification d'identité, litiges et absences, modération et
     * sécurité du compte. S'y ajoutent les types critiques de {@link NotificationTypes}, qui
     * déclenchent en plus un SMS de repli. La liste ne change rien à {@link #isAllowed} (un
     * type non mappé part déjà) : elle documente le choix et sert de référence au test de
     * classification. Tout préfixe {@code DISPUTE_} y est assimilé.
     */
    static final Set<String> ALWAYS_ON = Set.of(
            // Paiements et portefeuille
            "CARD_EXPIRING", "wallet_topup_confirmed", "WALLET_ADJUSTED",
            "STRIPE_ONBOARDING_INCOMPLETE",
            // Vérification d'identité
            "KYC_VERIFIED", "KYC_ACTION_REQUIRED", "KYC_RESET",
            // Litiges et absences
            "DISPUTE_UPDATED", "DISPUTE_RESOLVED", "SENDER_NOSHOW_REPORTED", "NOSHOW_DECISION",
            // Modération et plateforme
            "ADMIN_BROADCAST", "SYSTEM", "ADMIN_WARNING", "MESSAGING_MUTED",
            "ACCOUNT_SUSPENDED", "ACCOUNT_DELETION_CANCELLED", "REPORT_RESOLVED",
            "ANNOUNCEMENT_REMOVED"
    );

    /** Le type part-il quels que soient les réglages ? */
    static boolean isAlwaysOn(String type) {
        return type != null && (NotificationTypes.isCritical(type)
                || ALWAYS_ON.contains(type)
                || type.startsWith("DISPUTE_")
                || type.startsWith("ACCOUNT_"));
    }

    private final NotificationPrefsJpaRepository repository;
    private final UserRepository userRepository;

    public NotificationPrefsService(NotificationPrefsJpaRepository repository,
                                    UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPrefsDto getPrefs(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        return repository.findById(userId)
                .map(this::toDto)
                .orElse(NotificationPrefsDto.defaults());
    }

    public void upsert(String firebaseUid, NotificationPrefsDto dto) {
        UUID userId = resolveUserId(firebaseUid);
        NotificationPrefsEntity entity = repository.findById(userId)
                .orElseGet(() -> {
                    NotificationPrefsEntity e = new NotificationPrefsEntity();
                    e.setUserId(userId);
                    return e;
                });
        entity.setPushActivityBids(dto.pushActivityBids());
        entity.setPushActivityNegotiations(dto.pushActivityNegotiations());
        entity.setPushMessages(dto.pushMessages());
        entity.setPushTripReminder(dto.pushTripReminder());
        entity.setPushPromo(dto.pushPromo());
        entity.setPushCorridorAlerts(dto.pushCorridorAlerts());
        // null = champ absent (application antérieure à V305) : on garde la valeur stockée.
        if (dto.pushMissedCalls() != null) entity.setPushMissedCalls(dto.pushMissedCalls());
        if (dto.pushTravelerAutomations() != null) {
            entity.setPushTravelerAutomations(dto.pushTravelerAutomations());
        }
        if (dto.pushRemindersTips() != null) entity.setPushRemindersTips(dto.pushRemindersTips());
        repository.save(entity);
    }

    /**
     * Cloche « Colis sur mes trajets » : l'état du toggle pour le voyageur courant.
     * Défaut {@code true} si aucune ligne de préférence n'existe encore.
     */
    @Transactional(readOnly = true)
    public boolean getPackageMatchAlert(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        return repository.findById(userId)
                .map(NotificationPrefsEntity::isPushTripPackageMatch)
                .orElse(true);
    }

    /** Active/coupe la notif temps réel « un colis matche un de mes trajets ». */
    public void setPackageMatchAlert(String firebaseUid, boolean enabled) {
        UUID userId = resolveUserId(firebaseUid);
        NotificationPrefsEntity entity = repository.findById(userId)
                .orElseGet(() -> {
                    NotificationPrefsEntity e = new NotificationPrefsEntity();
                    e.setUserId(userId);
                    return e;
                });
        entity.setPushTripPackageMatch(enabled);
        repository.save(entity);
    }

    /** Gate côté listener (par UUID interne) : le voyageur veut-il les matchs colis ? */
    @Transactional(readOnly = true)
    public boolean isPackageMatchEnabled(UUID userId) {
        return repository.findById(userId)
                .map(NotificationPrefsEntity::isPushTripPackageMatch)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(UUID userId, String notificationType) {
        if (notificationType == null) return true;
        if (isAlwaysOn(notificationType)) return true;
        String prefKey = TYPE_TO_PREF.get(notificationType);
        if (prefKey == null) return true;
        return repository.findById(userId)
                .map(prefs -> getPrefValue(prefs, prefKey))
                .orElse(true);
    }

    private boolean getPrefValue(NotificationPrefsEntity prefs, String prefKey) {
        return switch (prefKey) {
            case "pushActivityBids"         -> prefs.isPushActivityBids();
            case "pushActivityNegotiations" -> prefs.isPushActivityNegotiations();
            case "pushMessages"             -> prefs.isPushMessages();
            case "pushTripReminder"         -> prefs.isPushTripReminder();
            case "pushPromo"                -> prefs.isPushPromo();
            case "pushCorridorAlerts"       -> prefs.isPushCorridorAlerts();
            case "pushTripPackageMatch"     -> prefs.isPushTripPackageMatch();
            case "pushMissedCalls"          -> prefs.isPushMissedCalls();
            case "pushTravelerAutomations"  -> prefs.isPushTravelerAutomations();
            case "pushRemindersTips"        -> prefs.isPushRemindersTips();
            default                         -> true;
        };
    }

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user_not_found",
                        "User not found", "Utilisateur introuvable"));
    }

    private NotificationPrefsDto toDto(NotificationPrefsEntity e) {
        return new NotificationPrefsDto(
                e.isPushActivityBids(),
                e.isPushActivityNegotiations(),
                e.isPushMessages(),
                e.isPushTripReminder(),
                e.isPushPromo(),
                e.isPushCorridorAlerts(),
                e.isPushMissedCalls(),
                e.isPushTravelerAutomations(),
                e.isPushRemindersTips()
        );
    }
}
