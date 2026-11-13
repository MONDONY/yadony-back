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
 * demandé à l'expéditeur, aucune date de remise ne bouge. Le nombre de retards n'est pas limité ; deux
 * annonces identiques de suite sont deux retards (aucun mécanisme d'idempotence HTTP n'existe sur ces
 * endpoints, hors paiements Stripe).
 *
 * <p>Verrous dans le même ordre que {@link TripRescheduleService} et {@code CancellationService} : le
 * trajet d'abord, puis ses colis en route par date de création, pour qu'aucune paire de transactions ne
 * se croise.
 */
@Service
public class TripDelayService {

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
        assertRange(announcement, request);

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
        delay.setMessage(request.message() == null || request.message().isBlank() ? null : request.message().trim());
        delay.setCreatedBy(traveler.getId());
        TripDelayEntity saved = delayRepository.save(delay);

        announcement.setArrivalDate(request.arrivalDate());
        // La fin de fourchette n'existe que sur un bateau (chk_announcements_boat_columns).
        announcement.setArrivalDateMax(announcement.getTransportMode() == TransportMode.BOAT
                ? request.arrivalDateMax() : null);
        // Le rappel « êtes-vous arrivé ? » repart pour la nouvelle date, comme au report.
        announcement.setArrivalPromptSentAt(null);
        announcementRepository.save(announcement);

        List<TripDelayedEvent.Target> targets = applyToBids(announcement);

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

    /**
     * Fourchette demandée : début pas avant le départ et fin pas avant le début (422
     * {@code arrival-range-invalid}), fin au plus départ + 120 jours (422 {@code arrival-too-far}). Hors
     * bateau, le début compte seul sur l'annonce : il reste soumis à la durée maximale du mode.
     */
    private static void assertRange(AnnouncementEntity announcement, TripDelayRequest request) {
        LocalDate departure = announcement.getDepartureDate();
        if (departure != null && request.arrivalDate().isBefore(departure)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-range-invalid",
                    "problem.arrival-range-invalid");
        }
        ArrivalRules.validateRange(departure, request.arrivalDate(), request.arrivalDateMax());
        if (announcement.getTransportMode() != TransportMode.BOAT) {
            ArrivalRules.validate(departure, null, request.arrivalDate(), null, announcement.getTransportMode());
        }
    }

    /**
     * Colis non livrés, c'est-à-dire en route (remis, en transit, arrivés) : le code de retrait part de la
     * nouvelle fin de fourchette, pour qu'un navire en retard n'accoste pas après l'expiration. Les colis
     * livrés ou annulés ne sont ni modifiés ni prévenus.
     */
    private List<TripDelayedEvent.Target> applyToBids(AnnouncementEntity announcement) {
        List<TripDelayedEvent.Target> targets = new ArrayList<>();
        for (BidEntity bid : bidRepository.findHandedOverByAnnouncementIdForUpdate(announcement.getId())) {
            if (bid.getConfirmationCode() != null) {
                bid.setConfirmationCodeExpiry(ArrivalRules.pickupCodeExpiry(announcement));
                bidRepository.save(bid);
            }
            targets.add(new TripDelayedEvent.Target(bid.getId(), bid.getSenderId()));
        }
        return targets;
    }
}
