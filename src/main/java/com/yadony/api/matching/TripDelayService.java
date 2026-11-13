package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.TripDelayInfo;
import com.yadony.api.matching.dto.TripDelayRequest;
import com.yadony.api.matching.events.TripDelayedEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Annonce de retard d'un trajet en cours (traversée en bateau surtout, mais ouverte à tous les modes) :
 * le voyageur donne la nouvelle fourchette d'arrivée estimée et le motif, chaque expéditeur d'un colis
 * non livré en est prévenu (notification, e-mail, message dans la conversation du colis).
 *
 * <p>Contrairement au report ({@link TripRescheduleService}), le départ a eu lieu : aucun choix n'est
 * demandé à l'expéditeur, aucune date de remise ne bouge. Le total de retards n'est pas limité, mais le
 * rythme l'est : sous le verrou du trajet, une annonce identique à la dernière de moins de
 * {@value #REPLAY_WINDOW_MINUTES} minutes (double appui, réessai) renvoie ce retard sans rien écrire ni
 * renotifier, et au-delà de {@value #MAX_DELAYS_PER_DAY} retards en 24 heures c'est 409
 * {@code trip-delay-too-frequent}.
 *
 * <p>Verrous dans le même ordre que {@link TripRescheduleService} et {@code CancellationService} : le
 * trajet d'abord, puis ses colis en route par date de création, pour qu'aucune paire de transactions ne
 * se croise.
 */
@Service
public class TripDelayService {

    /** Une annonce identique à la dernière de moins de ce délai est un rejeu (double appui, réessai). */
    static final int REPLAY_WINDOW_MINUTES = 10;
    /** Retards au plus par trajet sur 24 heures glissantes. */
    static final int MAX_DELAYS_PER_DAY = 5;

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final TripDelayRepository delayRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public TripDelayService(AnnouncementRepository announcementRepository,
                            BidRepository bidRepository,
                            UserRepository userRepository,
                            TripDelayRepository delayRepository,
                            AuditService auditService,
                            ApplicationEventPublisher eventPublisher) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.delayRepository = delayRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    // Les listes de colis et la recherche portent la fourchette d'arrivée et latestTripDelay.
    @CacheEvict(value = {"announcements-search", "traveler-bids-me", "bids-me"}, allEntries = true)
    public TripDelayInfo announce(UUID announcementId, String firebaseUid, TripDelayRequest request) {
        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "announcement-not-found", "problem.announcement-not-found"));
        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "forbidden", "problem.forbidden.autorise-annoncer-retard");
        }
        if (announcement.getStatus() != AnnouncementStatus.IN_PROGRESS) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "trip-delay-not-allowed", "problem.trip-delay-not-allowed");
        }

        // Sous le verrou du trajet : un second appel simultané attend ici, puis voit la ligne validée.
        String message = normalizedMessage(request.message());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<TripDelayEntity> lastDay = delayRepository
                .findByAnnouncementIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(announcement.getId(), now.minusDays(1));
        if (!lastDay.isEmpty() && isReplay(lastDay.get(0), request, message, now)) {
            return TripDelayInfo.from(lastDay.get(0));
        }
        assertRange(announcement, request);
        if (lastDay.size() >= MAX_DELAYS_PER_DAY) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "trip-delay-too-frequent",
                    "problem.trip-delay-too-frequent", MAX_DELAYS_PER_DAY);
        }

        LocalDate previousArrivalDate = announcement.getArrivalDate();
        LocalDate previousArrivalDateMax = announcement.getArrivalDateMax();

        TripDelayEntity delay = new TripDelayEntity();
        delay.setAnnouncementId(announcement.getId());
        delay.setReason(request.reason());
        delay.setPreviousArrivalDate(previousArrivalDate);
        delay.setPreviousArrivalDateMax(previousArrivalDateMax);
        // La ligne garde la fourchette demandée même hors bateau (new_arrival_date_max NOT NULL).
        delay.setNewArrivalDate(request.arrivalDate());
        delay.setNewArrivalDateMax(request.arrivalDateMax());
        delay.setMessage(message);
        delay.setCreatedBy(traveler.getId());
        TripDelayEntity saved = delayRepository.save(delay);

        announcement.setArrivalDate(request.arrivalDate());
        // La fin de fourchette n'existe que sur un bateau (chk_announcements_boat_columns).
        announcement.setArrivalDateMax(announcement.getTransportMode() == TransportMode.BOAT
                ? request.arrivalDateMax() : null);
        // Le rappel « êtes-vous arrivé ? » repart pour la nouvelle date, comme au report.
        announcement.setArrivalPromptSentAt(null);
        announcementRepository.save(announcement);

        List<TripDelayedEvent.Target> targets = applyToBids(announcement, request.arrivalDateMax());

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("delayId", String.valueOf(saved.getId()));
        audit.put("reason", request.reason().name());
        audit.put("previousArrivalDate", String.valueOf(previousArrivalDate));
        audit.put("previousArrivalDateMax", String.valueOf(previousArrivalDateMax));
        audit.put("newArrivalDate", request.arrivalDate().toString());
        audit.put("newArrivalDateMax", request.arrivalDateMax().toString());
        audit.put("parcelsNotified", String.valueOf(targets.size()));
        auditService.log("ANNOUNCEMENT", announcement.getId(), "TRIP_DELAY_ANNOUNCED", traveler.getId(), audit);

        eventPublisher.publishEvent(new TripDelayedEvent(announcement.getId(), saved.getId(), traveler.getId(),
                request.reason().name(), request.arrivalDate(), request.arrivalDateMax(), saved.getMessage(), targets));

        return TripDelayInfo.from(saved);
    }

    private static String normalizedMessage(String message) {
        return message == null || message.isBlank() ? null : message.trim();
    }

    /** Même motif, même fourchette, même message que le dernier retard, annoncé il y a moins de 10 minutes. */
    private static boolean isReplay(TripDelayEntity last, TripDelayRequest request, String message,
                                    LocalDateTime now) {
        return last.getCreatedAt() != null
                && last.getCreatedAt().isAfter(now.minusMinutes(REPLAY_WINDOW_MINUTES))
                && last.getReason() == request.reason()
                && last.getNewArrivalDate().equals(request.arrivalDate())
                && last.getNewArrivalDateMax().equals(request.arrivalDateMax())
                && java.util.Objects.equals(last.getMessage(), message);
    }

    /**
     * Fourchette demandée, valable pour tous les modes : motif propre au bateau refusé ailleurs (422
     * {@code trip-delay-reason-invalid}) ; début pas avant le départ (422 {@code arrival-range-invalid}) et
     * fin pas avant le début ni avant aujourd'hui (même code) ; fin au plus départ + 120 jours pour un
     * bateau, sinon dans la durée maximale du mode (422 {@code arrival-too-far}, 3 jours avion, 14 jours
     * route) : la fin compte aussi hors bateau, c'est elle qui cale l'expiration du code de retrait.
     */
    private static void assertRange(AnnouncementEntity announcement, TripDelayRequest request) {
        TransportMode mode = announcement.getTransportMode();
        if (request.reason() == TripDelayReason.PORT_CONGESTION && mode != TransportMode.BOAT) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "trip-delay-reason-invalid",
                    "problem.trip-delay-reason-invalid");
        }
        LocalDate departure = announcement.getDepartureDate();
        if (departure != null && request.arrivalDate().isBefore(departure)) {
            // Code imposé par le brief, détail propre à ce cas.
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-range-invalid",
                    "problem.arrival-before-departure");
        }
        ArrivalRules.validateRange(departure, request.arrivalDate(), request.arrivalDateMax());
        if (request.arrivalDateMax().isBefore(today(announcement))) {
            // Une fin passée n'est pas un retard : elle ferait expirer les codes de retrait des colis en route.
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-range-invalid",
                    "problem.arrival-range-past");
        }
        if (mode != TransportMode.BOAT) {
            ArrivalRules.validate(departure, null, request.arrivalDateMax(), null, mode);
        }
    }

    /** Aujourd'hui dans le fuseau du trajet (UTC si absent ou illisible). */
    private static LocalDate today(AnnouncementEntity announcement) {
        ZoneId zone = ZoneOffset.UTC;
        if (announcement.getTimezone() != null) {
            try {
                zone = ZoneId.of(announcement.getTimezone());
            } catch (java.time.DateTimeException ignored) {
                // Fuseau illisible : UTC.
            }
        }
        return LocalDate.now(zone);
    }

    /**
     * Colis non livrés, c'est-à-dire en route (remis, en transit, arrivés) : le code de retrait part de la
     * FIN de la nouvelle fourchette (la demandée, aussi hors bateau où l'annonce ne garde que le début), pour
     * qu'un navire ou un véhicule en retard n'arrive pas après l'expiration, et vaut au moins 24 h. Les colis
     * livrés ou annulés ne sont ni modifiés ni prévenus.
     */
    private List<TripDelayedEvent.Target> applyToBids(AnnouncementEntity announcement, LocalDate arrivalDateMax) {
        List<TripDelayedEvent.Target> targets = new ArrayList<>();
        for (BidEntity bid : bidRepository.findHandedOverByAnnouncementIdForUpdate(announcement.getId())) {
            if (bid.getConfirmationCode() != null) {
                bid.setConfirmationCodeExpiry(
                        ArrivalRules.renewedPickupCodeExpiry(arrivalDateMax, announcement.getArrivalTime()));
                bidRepository.save(bid);
            }
            targets.add(new TripDelayedEvent.Target(bid.getId(), bid.getSenderId()));
        }
        return targets;
    }
}
