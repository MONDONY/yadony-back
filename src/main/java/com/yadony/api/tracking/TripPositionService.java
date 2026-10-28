package com.yadony.api.tracking;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TripTimezones;
import com.yadony.api.tracking.PositionBlurService.BlurredPosition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enregistre les positions floues d'un voyageur (suivi approximatif). Les coordonnées brutes
 * ne sont passées qu'à {@link PositionBlurService#blur} : jamais stockées, journalisées, ni
 * placées dans un message d'exception ou un payload d'audit.
 */
@Service
public class TripPositionService {

    /** La fenêtre s'ouvre 30 minutes avant l'heure de départ. */
    static final Duration WINDOW_LEAD = Duration.ofMinutes(30);
    /** Un échantillon au plus toutes les 15 minutes par trajet (hors arrivée). */
    static final Duration THROTTLE = Duration.ofMinutes(15);

    private static final List<BidStatus> CARRIED = List.of(BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT);

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final TripPositionSampleRepository sampleRepository;
    private final PositionBlurService blurService;
    private final AuditService auditService;
    private final Clock clock;

    public TripPositionService(AnnouncementRepository announcementRepository,
                               BidRepository bidRepository,
                               UserRepository userRepository,
                               TripPositionSampleRepository sampleRepository,
                               PositionBlurService blurService,
                               AuditService auditService,
                               Clock clock) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.sampleRepository = sampleRepository;
        this.blurService = blurService;
        this.auditService = auditService;
        this.clock = clock;
    }

    private boolean windowOpen(AnnouncementEntity a, OffsetDateTime now) {
        return isWindowOpen(a, now, () -> bidRepository.existsByAnnouncementIdAndStatusIn(a.getId(), CARRIED));
    }

    /** Statuts de colis qui ouvrent la fenêtre (colis pris en charge, pas encore arrivé). */
    public static List<BidStatus> carriedStatuses() {
        return CARRIED;
    }

    /**
     * Règle de la fenêtre, partagée avec la liste « Mes trajets » (qui lit « un colis porté » par
     * lot) : {@code now} >= départ - 30 min, puis au moins un colis porté.
     */
    public static boolean isWindowOpen(AnnouncementEntity a, OffsetDateTime now,
                                       java.util.function.BooleanSupplier hasCarriedParcel) {
        OffsetDateTime departure = departureInstant(a);
        if (departure == null || now.isBefore(departure.minus(WINDOW_LEAD))) {
            return false;
        }
        return hasCarriedParcel.getAsBoolean();
    }

    /** Instant de départ prévu (partagé avec le rappel de départ). */
    static OffsetDateTime departureInstant(AnnouncementEntity a) {
        if (a.getDepartureAt() != null) {
            return a.getDepartureAt();
        }
        if (a.getDepartureDate() == null) {
            return null;
        }
        var zone = TripTimezones.zoneOf(a.getTimezone());
        var time = a.getDepartureTime() != null ? a.getDepartureTime() : java.time.LocalTime.MIDNIGHT;
        return a.getDepartureDate().atTime(time).atZone(zone).toOffsetDateTime();
    }

    /** Variante pour les contrôleurs, qui ne connaissent que l'uid Firebase. */
    @Transactional
    public Optional<TripPositionSampleEntity> recordForFirebaseUid(
            UUID announcementId, String firebaseUid, Double lat, Double lon, SampleSource source) {
        return record(announcementId, travelerIdOf(firebaseUid), lat, lon, source);
    }

    /**
     * Enregistre une position floue. Vide (sans erreur) quand la fenêtre est fermée, que les
     * coordonnées manquent, qu'aucune ville n'est trouvée ou que le dernier échantillon du
     * trajet a moins de 15 minutes (sauf {@link SampleSource#ARRIVEE}). 403 si l'appelant
     * n'est pas le voyageur du trajet.
     */
    @Transactional
    public Optional<TripPositionSampleEntity> record(UUID announcementId, UUID travelerId,
                                                     Double lat, Double lon, SampleSource source) {
        AnnouncementEntity announcement = loadOwned(announcementId, travelerId);
        return store(announcement, lat, lon, source);
    }

    /**
     * Marque le départ (idempotent : la première valeur est conservée), journalise l'action et
     * tente un échantillon {@link SampleSource#DEPART}. Réussit même sans coordonnées.
     */
    @Transactional
    public void depart(UUID announcementId, String firebaseUid, Double lat, Double lon) {
        UUID travelerId = travelerIdOf(firebaseUid);
        AnnouncementEntity announcement = loadOwned(announcementId, travelerId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (announcement.getDepartedAt() == null) {
            if (!canDepart(announcement, now)) {
                // Trop tôt ou trajet clos : on répond 200 sans rien changer.
                return;
            }
            announcement.setDepartedAt(now);
            announcementRepository.save(announcement);
            auditService.log("ANNOUNCEMENT", announcement.getId(), "TRIP_DEPARTED", travelerId,
                    Map.of("hasPosition", lat != null && lon != null));
        }
        store(announcement, lat, lon, SampleSource.DEPART);
    }

    /** « Je pars » n'a d'effet qu'à partir de départ - 30 min, sur un trajet encore en cours. */
    private static boolean canDepart(AnnouncementEntity a, OffsetDateTime now) {
        if (a.getStatus() != AnnouncementStatus.ACTIVE && a.getStatus() != AnnouncementStatus.FULL
                && a.getStatus() != AnnouncementStatus.IN_PROGRESS) {
            return false;
        }
        OffsetDateTime departure = departureInstant(a);
        return departure != null && !now.isBefore(departure.minus(WINDOW_LEAD));
    }

    private Optional<TripPositionSampleEntity> store(AnnouncementEntity a, Double lat, Double lon,
                                                     SampleSource source) {
        if (lat == null || lon == null) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (source == SampleSource.ARRIVEE) {
            // Au moment de l'arrivée les colis sont déjà passés en ARRIVED : seule l'heure compte.
            OffsetDateTime departure = departureInstant(a);
            if (departure == null || now.isBefore(departure.minus(WINDOW_LEAD))) {
                return Optional.empty();
            }
        } else if (!windowOpen(a, now)) {
            return Optional.empty();
        }
        if (source != SampleSource.ARRIVEE) {
            Optional<TripPositionSampleEntity> last =
                    sampleRepository.findFirstByAnnouncementIdOrderByCapturedAtDesc(a.getId());
            if (last.isPresent() && last.get().getCapturedAt().isAfter(now.minus(THROTTLE))) {
                return Optional.empty();
            }
        }
        BlurredPosition blurred = blurService.blur(lat, lon,
                a.getDepartureCity(), a.getDepartureCountryCode(),
                a.getArrivalCity(), a.getArrivalCountryCode());
        if (blurred.cityName() == null || blurred.countryCode() == null) {
            return Optional.empty();
        }
        return Optional.of(sampleRepository.save(new TripPositionSampleEntity(
                a.getId(), now, blurred.cityName(), blurred.countryCode(), blurred.progressPct(), source)));
    }

    private UUID travelerIdOf(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"))
                .getId();
    }

    private AnnouncementEntity loadOwned(UUID announcementId, UUID travelerId) {
        AnnouncementEntity announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à modifier ce trajet");
        }
        return announcement;
    }
}
