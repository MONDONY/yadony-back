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
     *
     * <p>Réglages par rôle (V340) : {@code pushActivityBids} (« Mes envois ») suit ce que
     * reçoit l'expéditeur, {@code pushMyTrips} ce que reçoit le voyageur et
     * {@code pushRecipientParcels} ce que reçoit le destinataire d'un colis. Un type envoyé
     * aux deux parties d'un colis engagé ({@link #SENDER_OR_TRAVELER}) part si l'un des deux
     * premiers est activé : le type seul ne dit pas à quel titre la personne le reçoit, et
     * couper par erreur un retour ou une échéance coûte plus cher qu'un push de trop.
     */
    static final String SENDER_OR_TRAVELER = "pushMyParcelsOrTrips";

    static final Map<String, String> TYPE_TO_PREF = Map.ofEntries(
            Map.entry("BID_CREATED",                  "pushMyTrips"),
            Map.entry("BID_ACCEPTED",                 "pushActivityBids"),
            Map.entry("BID_REJECTED",                 "pushActivityBids"),
            Map.entry("BID_CANCELLED_BY_ADMIN",       SENDER_OR_TRAVELER),
            Map.entry("PARCEL_REFUSED",               "pushActivityBids"),
            // Supplément de prix à la remise : une réponse attendue ou une issue du colis.
            Map.entry("BID_SUPPLEMENT_REQUESTED",     "pushActivityBids"),
            Map.entry("BID_SUPPLEMENT_PAID",          "pushMyTrips"),
            Map.entry("BID_SUPPLEMENT_DECLINED",      "pushMyTrips"),
            Map.entry("BID_SUPPLEMENT_EXPIRED",       SENDER_OR_TRAVELER),
            Map.entry("BID_SUPPLEMENT_WITHDRAWN",     "pushActivityBids"),
            Map.entry("BID_SUPPLEMENT_REFUNDED",      "pushActivityBids"),
            Map.entry("BID_SUPPLEMENT_PAID_OUT",      "pushMyTrips"),
            // Modification d'un colis accepté : ce que reçoit le voyageur (demande, rappel) suit « Mes
            // trajets », ce que reçoit l'expéditeur (acceptée, refusée, non apportée) « Mes envois » ;
            // annulée, expirée et remboursée vont, selon le cas, à l'un ou à l'autre.
            Map.entry("BID_AMENDMENT_REQUESTED",      "pushMyTrips"),
            Map.entry("BID_AMENDMENT_REMINDER",       "pushMyTrips"),
            Map.entry("BID_AMENDMENT_CANCELLED",      SENDER_OR_TRAVELER),
            Map.entry("BID_AMENDMENT_ACCEPTED",       "pushActivityBids"),
            Map.entry("BID_AMENDMENT_DECLINED",       "pushActivityBids"),
            Map.entry("BID_AMENDMENT_EXPIRED",        SENDER_OR_TRAVELER),
            Map.entry("BID_AMENDMENT_NOT_BROUGHT",    "pushActivityBids"),
            Map.entry("BID_AMENDMENT_REFUNDED",       SENDER_OR_TRAVELER),
            Map.entry("BID_EXPIRED",                  SENDER_OR_TRAVELER),
            Map.entry("TRIP_CANCELLED",               "pushActivityBids"),
            Map.entry("TRIP_RESCHEDULE_KEPT",         "pushMyTrips"),
            Map.entry("TRIP_RESCHEDULE_WITHDRAWN",    "pushMyTrips"),
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
            // FLUTTER-H7 : prix accepté en espèces, commission à régler depuis le fil.
            Map.entry("BID_NEGOTIATION_COMMISSION_DUE",  "pushActivityNegotiations"),
            // Famille « quelqu'un répond à mon colis » : ces trois-là appellent une action de
            // l'expéditeur et suivent donc le même interrupteur que les offres reçues.
            Map.entry("TRAVELER_INVITE",              "pushActivityBids"),
            Map.entry("CONFIRMATION_CODE_READY",      "pushActivityBids"),
            Map.entry("CONFIRMATION_CODE_BLOCKED",    "pushActivityBids"),
            Map.entry("CONFIRMATION_CODE_REQUESTED",  "pushActivityBids"),
            Map.entry("DELIVERY_NOSHOW_REPORTED",     SENDER_OR_TRAVELER),
            Map.entry("DELIVERY_RETRY_APPOINTMENT",   "pushMyTrips"),
            Map.entry("PARCEL_UNCLAIMED",             SENDER_OR_TRAVELER),
            Map.entry("MM_PAYMENT_PENDING",           "pushActivityBids"),
            Map.entry("MM_PAYMENT_EXPIRED",           SENDER_OR_TRAVELER),
            Map.entry("BID_CANCELLED_BEFORE_PAYMENT", "pushMyTrips"),
            Map.entry("MOBILE_MONEY_PAYMENT_CONFIRMED", SENDER_OR_TRAVELER),
            Map.entry("MOBILE_MONEY_PAYMENT_FAILED",  "pushActivityBids"),
            Map.entry("PARCEL_RETURNED",              SENDER_OR_TRAVELER),
            Map.entry("PARCEL_RETURN_REQUIRED",       "pushActivityBids"),
            Map.entry("PARCEL_RETURN_TO_SENDER",      "pushMyTrips"),
            Map.entry("RETURN_DEADLINE_WARNING",      SENDER_OR_TRAVELER),
            Map.entry("RETURN_DEADLINE_EXPIRED",      SENDER_OR_TRAVELER),
            // Suivi du colis par son destinataire (lot 2) et réponses relayées à l'expéditeur.
            Map.entry("RECIPIENT_PARCEL_INCOMING",    "pushRecipientParcels"),
            Map.entry("RECIPIENT_PARCEL_ANNOUNCED",   "pushRecipientParcels"),
            Map.entry("RECIPIENT_PARCEL_DEPARTED",    "pushRecipientParcels"),
            Map.entry("RECIPIENT_PARCEL_ARRIVED",     "pushRecipientParcels"),
            Map.entry("RECIPIENT_PARCEL_DELIVERED",   "pushRecipientParcels"),
            Map.entry("RECIPIENT_PARCEL_CANCELLED",   "pushRecipientParcels"),
            Map.entry("RECIPIENT_PARCEL_RESCHEDULED", "pushRecipientParcels"),
            Map.entry("RECIPIENT_PICKUP_UPDATED",     "pushRecipientParcels"),
            Map.entry("RECIPIENT_CONFIRMED",          "pushActivityBids"),
            Map.entry("RECIPIENT_DECLINED",           SENDER_OR_TRAVELER),
            Map.entry("RECIPIENT_WITHDRAWN",          SENDER_OR_TRAVELER),
            Map.entry("RECIPIENT_REPLACEMENT_REQUESTED", "pushActivityBids"),
            // Changement de destinataire (lot 3A) : ancien destinataire et voyageur.
            Map.entry("RECIPIENT_PARCEL_REASSIGNED",  "pushRecipientParcels"),
            Map.entry("RECIPIENT_CHANGED",            "pushMyTrips"),
            // Invitations au carnet de destinataires (lot 4).
            Map.entry("RECIPIENT_INVITATION",         "pushRecipientParcels"),
            Map.entry("RECIPIENT_INVITATION_ACCEPTED", "pushActivityBids"),
            Map.entry("RECIPIENT_INVITATION_REMOVED", "pushRecipientParcels"),
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
            // Alerte prix d'un billet d'avion (package flights) : une alerte posée par
            // l'utilisateur, même interrupteur que ses autres alertes.
            Map.entry("FLIGHT_PRICE_ALERT",           "pushCorridorAlerts"),
            // FLUTTER-JH : « de la place s'est libérée » sur un trajet suivi. Même famille
            // (on me signale un trajet) ; l'entrée du centre de notifications reste si le
            // push est coupé, c'est le repli dans l'app.
            Map.entry("TRIP_SPACE_FREED",             "pushCorridorAlerts"),
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
            "CARD_EXPIRING", "wallet_topup_confirmed", "wallet_topup_failed", "WALLET_ADJUSTED",
            "STRIPE_ONBOARDING_INCOMPLETE",
            // Virement Stripe vers la banque du voyageur (arrivé, échoué)
            "BANK_PAYOUT_PAID", "BANK_PAYOUT_FAILED",
            // Récompense de parrainage à choisir (crédit de solde ou code personnel)
            "REFERRAL_REWARD_AVAILABLE",
            // Décision de l'admin sur le badge GP Pro
            "GP_PRO_APPROVED", "GP_PRO_REJECTED", "GP_PRO_REVOKED",
            // Vérification d'identité
            "KYC_VERIFIED", "KYC_ACTION_REQUIRED", "KYC_RESET",
            // Litiges et absences
            "DISPUTE_RESOLVED", "SENDER_NOSHOW_REPORTED", "NOSHOW_DECISION",
            // Modération et plateforme
            "ADMIN_BROADCAST", "SYSTEM", "ADMIN_WARNING", "MESSAGING_MUTED",
            "ACCOUNT_SUSPENDED", "ACCOUNT_DELETION_CANCELLED", "REPORT_RESOLVED",
            "ANNOUNCEMENT_REMOVED"
    );

    /**
     * Types qui ne demandent aucune action à celui qui les reçoit : ils restent dans le feed
     * in-app mais ne font jamais sonner le téléphone, quels que soient les réglages (y compris
     * ceux de {@link #ALWAYS_ON}). Plan de réduction du bruit, étape 2 : bonne nouvelle sans
     * suite à donner, information sur une action que la personne vient de faire elle-même,
     * ou expiration qu'elle découvrira en ouvrant l'app.
     */
    static final Set<String> FEED_ONLY = Set.of(
            // L'utilisateur vient d'agir lui-même, ou l'autre partie l'informe sans rien demander
            "negotiation_deposit_pending", "TRIP_RESCHEDULE_KEPT", "BID_CANCELLED_BEFORE_PAYMENT",
            // Destinataire : il a lui-même donné le code, ou n'est plus concerné
            "RECIPIENT_PARCEL_DELIVERED", "RECIPIENT_PARCEL_REASSIGNED",
            // Expéditeur et voyageur : bonne nouvelle ou changement visible au moment d'agir
            "RECIPIENT_CONFIRMED", "RECIPIENT_CHANGED",
            "RECIPIENT_INVITATION_ACCEPTED", "RECIPIENT_INVITATION_REMOVED",
            // Expirations découvertes à l'ouverture de l'app
            "request_expired", "bid_negotiation_expired",
            // Suite d'un signalement, consultable dans la boîte Annonces
            "REPORT_RESOLVED"
    );

    /** Le type reste-t-il dans le feed sans jamais partir en push ? */
    public static boolean isFeedOnly(String type) {
        return type != null && FEED_ONLY.contains(type);
    }

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
        // V340 : une application qui ne connaît que « Offres » ne les envoie pas. Son choix
        // vaut alors pour les trois rôles, comme avant le découpage.
        entity.setPushMyTrips(dto.pushMyTrips() != null
                ? dto.pushMyTrips() : dto.pushActivityBids());
        entity.setPushRecipientParcels(dto.pushRecipientParcels() != null
                ? dto.pushRecipientParcels() : dto.pushActivityBids());
        if (dto.emailRecaps() != null) entity.setEmailRecaps(dto.emailRecaps());
        if (dto.emailAlertsTips() != null) entity.setEmailAlertsTips(dto.emailAlertsTips());
        repository.save(entity);
    }

    /**
     * L'e-mail de cette famille peut-il partir ? Les reçus, décisions et démarches du compte
     * partent toujours ; les récapitulatifs et les alertes et conseils suivent leur réglage.
     * Sans ligne de préférence, tout part.
     */
    @Transactional(readOnly = true)
    public boolean isEmailAllowed(UUID userId, NotificationEmailPolicy.Kind kind) {
        if (kind == null) return false;
        return kind.pref()
                .map(pref -> repository.findById(userId)
                        .map(prefs -> "emailRecaps".equals(pref) ? prefs.isEmailRecaps() : prefs.isEmailAlertsTips())
                        .orElse(true))
                .orElse(true);
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
            case "pushMyTrips"              -> prefs.isPushMyTrips();
            case "pushRecipientParcels"     -> prefs.isPushRecipientParcels();
            case SENDER_OR_TRAVELER         -> prefs.isPushActivityBids() || prefs.isPushMyTrips();
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
                e.isPushRemindersTips(),
                e.isPushMyTrips(),
                e.isPushRecipientParcels(),
                e.isEmailRecaps(),
                e.isEmailAlertsTips()
        );
    }
}
