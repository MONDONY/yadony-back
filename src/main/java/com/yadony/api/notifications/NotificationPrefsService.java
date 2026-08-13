package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class NotificationPrefsService {

    private static final Map<String, String> TYPE_TO_PREF = Map.ofEntries(
            Map.entry("BID_CREATED",                  "pushActivityBids"),
            Map.entry("BID_ACCEPTED",                 "pushActivityBids"),
            Map.entry("BID_REJECTED",                 "pushActivityBids"),
            Map.entry("PARCEL_REFUSED",               "pushActivityBids"),
            Map.entry("BID_EXPIRED",                  "pushActivityBids"),
            Map.entry("TRIP_CANCELLED",               "pushActivityBids"),
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
            // Famille « quelqu'un répond à mon colis » : ces trois-là appellent une action de
            // l'expéditeur et suivent donc le même interrupteur que les offres reçues.
            Map.entry("TRAVELER_INVITE",              "pushActivityBids"),
            Map.entry("CONFIRMATION_CODE_READY",      "pushActivityBids"),
            Map.entry("DELIVERY_NOSHOW_REPORTED",     "pushActivityBids"),
            Map.entry("MM_PAYMENT_PENDING",           "pushActivityBids"),
            Map.entry("NEW_MESSAGE",                  "pushMessages"),
            Map.entry("TRIP_IN_PROGRESS",             "pushTripReminder"),
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
            Map.entry("PACKAGE_MATCH",                "pushTripPackageMatch")
    );

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
        if (NotificationTypes.isCritical(notificationType)) return true;
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
                e.isPushCorridorAlerts()
        );
    }
}
