package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.TripRescheduleRequest;
import com.yadony.api.matching.dto.TripRescheduleResponse;
import com.yadony.api.matching.events.TripRescheduledEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Report d'un trajet publié : vol annulé, voyage repoussé. Le voyageur change la date
 * même avec des colis acceptés, ce que la modification classique interdit. En
 * contrepartie, chaque expéditeur dont le colis est accepté ou remis peut se retirer
 * sans frais ({@code cancellation.RescheduleDecisionService}), et le trajet ne peut être
 * reporté que {@link TripRescheduleRules#MAX_RESCHEDULES} fois.
 */
@Service
public class TripRescheduleService {

    /** Statuts depuis lesquels un trajet se reporte : publié, complet, ou passé « en cours »
     *  par le planificateur alors que le vol a été annulé à l'aéroport. */
    private static final Set<AnnouncementStatus> RESCHEDULABLE =
            EnumSet.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL, AnnouncementStatus.IN_PROGRESS);

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final TripRescheduleRepository rescheduleRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public TripRescheduleService(AnnouncementRepository announcementRepository,
                                 BidRepository bidRepository,
                                 UserRepository userRepository,
                                 TripRescheduleRepository rescheduleRepository,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.rescheduleRepository = rescheduleRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /** Retards annoncés (V382) : purgés au report ; aucun tant que Spring ne l'a pas injecté. */
    private TripDelayRepository tripDelayRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTripDelayRepository(TripDelayRepository tripDelayRepository) {
        this.tripDelayRepository = tripDelayRepository;
    }

    @Transactional
    @CacheEvict(value = {"announcements-search", "traveler-bids-me", "bids-me"}, allEntries = true)
    public TripRescheduleResponse reschedule(UUID announcementId, String firebaseUid, TripRescheduleRequest request) {
        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "announcement-not-found", "problem.announcement-not-found"));
        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "forbidden", "problem.forbidden.autorise-reporter-trajet");
        }
        assertReschedulable(announcement);
        assertNewSchedule(announcement, request);
        // Calculée avant d'écraser l'arrivée : elle part de la fourchette actuelle.
        java.time.LocalDate newArrivalDateMax = shiftedArrivalDateMax(announcement, request);

        TripRescheduleEntity reschedule = new TripRescheduleEntity();
        reschedule.setAnnouncementId(announcement.getId());
        reschedule.setTravelerId(traveler.getId());
        reschedule.setReason(request.reason());
        reschedule.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        reschedule.setPreviousDepartureDate(announcement.getDepartureDate());
        reschedule.setPreviousDepartureTime(announcement.getDepartureTime());
        reschedule.setPreviousArrivalDate(announcement.getArrivalDate());
        reschedule.setPreviousArrivalTime(announcement.getArrivalTime());
        reschedule.setPreviousHandoverDeadline(announcement.getHandoverDeadline());
        reschedule.setNewDepartureDate(request.departureDate());
        reschedule.setNewDepartureTime(request.departureTime());
        reschedule.setNewArrivalDate(request.arrivalDate());
        reschedule.setNewArrivalTime(request.arrivalTime());
        reschedule.setNewHandoverDeadline(request.handoverDeadline());
        rescheduleRepository.save(reschedule);

        announcement.setDepartureDate(request.departureDate());
        announcement.setDepartureTime(request.departureTime());
        announcement.setArrivalDate(request.arrivalDate());
        announcement.setArrivalDateMax(newArrivalDateMax);
        announcement.setArrivalTime(request.arrivalTime());
        announcement.setDepartureAt(AnnouncementService.deriveDepartureAt(
                request.departureDate(), request.departureTime(), announcement.getTimezone()));
        announcement.setHandoverDeadline(request.handoverDeadline());
        announcement.setRescheduleCount(announcement.getRescheduleCount() + 1);
        // Suivi voyageur : les rappels de départ et d'arrivée repartent pour la nouvelle date,
        // dans la même transaction que le report.
        announcement.setDeparturePromptSentAt(null);
        announcement.setArrivalPromptSentAt(null);
        announcement.setDepartedAt(null);
        if (announcement.getStatus() == AnnouncementStatus.IN_PROGRESS) {
            // Le planificateur l'avait fait partir à l'ancienne heure : le trajet repart
            // sur le marché, complet s'il ne reste plus de place.
            // Kilos (ou volume pour un bateau au m³) restants à zéro ; kilo libre jamais plein.
            boolean full = announcement.hasNoCapacityLeft();
            announcement.setStatus(full ? AnnouncementStatus.FULL : AnnouncementStatus.ACTIVE);
        }
        announcementRepository.save(announcement);
        clearAnnouncedDelays(announcement.getId());

        List<TripRescheduledEvent.Target> targets = applyToBids(announcement, reschedule.getId());
        int awaitingDecision = (int) targets.stream().filter(TripRescheduledEvent.Target::decisionRequired).count();

        Map<String, Object> audit = new HashMap<>();
        audit.put("rescheduleId", reschedule.getId().toString());
        audit.put("reason", request.reason().name());
        audit.put("previousDepartureDate", String.valueOf(reschedule.getPreviousDepartureDate()));
        audit.put("newDepartureDate", request.departureDate().toString());
        audit.put("rescheduleCount", String.valueOf(announcement.getRescheduleCount()));
        audit.put("parcelsAwaitingDecision", String.valueOf(awaitingDecision));
        auditService.log("ANNOUNCEMENT", announcement.getId(), "TRIP_RESCHEDULED", traveler.getId(), audit);

        eventPublisher.publishEvent(new TripRescheduledEvent(
                announcement.getId(), reschedule.getId(), traveler.getId(), request.reason().name(),
                reschedule.getPreviousDepartureDate(), request.departureDate(), request.departureTime(),
                targets));

        return new TripRescheduleResponse(reschedule.getId(), announcement.getRescheduleCount(),
                TripRescheduleRules.MAX_RESCHEDULES - announcement.getRescheduleCount(),
                awaitingDecision, targets.size() - awaitingDecision);
    }

    /**
     * Un report (le trajet repasse en vente, nouveau calendrier) rend caduques les retards annoncés avant :
     * ils sont retirés de la lecture ({@code latestTripDelay} redevient nul), la ligne et l'audit restent.
     */
    private void clearAnnouncedDelays(UUID announcementId) {
        if (tripDelayRepository == null) {
            return;
        }
        for (TripDelayEntity delay : tripDelayRepository.findByAnnouncementId(announcementId)) {
            delay.softDelete();
            tripDelayRepository.save(delay);
        }
    }

    private void assertReschedulable(AnnouncementEntity announcement) {
        if (!RESCHEDULABLE.contains(announcement.getStatus())) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "reschedule-invalid-status", "problem.reschedule-invalid-status");
        }
        if (bidRepository.existsByAnnouncementIdAndStatusIn(announcement.getId(),
                List.copyOf(TripRescheduleRules.BLOCKING_STATUSES))) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "reschedule-in-transit", "problem.reschedule-in-transit");
        }
        if (announcement.getRescheduleCount() >= TripRescheduleRules.MAX_RESCHEDULES) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "reschedule-limit-reached", "problem.reschedule-limit-reached", TripRescheduleRules.MAX_RESCHEDULES);
        }
    }

    private void assertNewSchedule(AnnouncementEntity announcement, TripRescheduleRequest request) {
        if (request.departureDate().equals(announcement.getDepartureDate())
                && Objects.equals(request.departureTime(), announcement.getDepartureTime())) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "reschedule-same-date", "problem.reschedule-same-date");
        }
        OffsetDateTime departureAt = AnnouncementService.deriveDepartureAt(
                request.departureDate(), request.departureTime(), announcement.getTimezone());
        if (departureAt != null && !departureAt.isAfter(OffsetDateTime.now())) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-departure-date", "problem.invalid-departure-date.nouvelle-date-depart-futur");
        }
        AnnouncementService.validateHandoverDeadline(request.handoverDeadline(),
                request.departureDate(), request.departureTime(), announcement.getTimezone());
        // Une limite déjà passée ouvrirait le signalement d'absence dès le report. Limite en
        // UTC, comparée à l'instant présent (YADONY-FLUTTER-PROD-1R).
        if (!HandoverDeadlineRules.deadlineInstant(request.handoverDeadline()).isAfter(Instant.now())) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "handover-deadline-past", "problem.handover-deadline-past");
        }
        ArrivalRules.validate(request.departureDate(), request.departureTime(),
                request.arrivalDate(), request.arrivalTime(), announcement.getTransportMode());
        // Bateau avec fourchette : le report doit garder un début de fourchette (sinon la fin n'a plus
        // de point de départ, chk_announcements_arrival_date_max).
        if (announcement.getArrivalDateMax() != null && request.arrivalDate() == null) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-range-invalid",
                    "problem.arrival-range-invalid");
        }
    }

    /**
     * Fin de la fourchette d'arrivée après le report. La requête de report ne porte qu'une date
     * d'arrivée : pour un bateau, la fourchette glisse avec elle en gardant sa largeur, sans dépasser
     * départ + 120 jours (plafond du CHECK, la fourchette est alors raccourcie ; la nouvelle arrivée
     * est déjà validée sous ce plafond par {@link ArrivalRules#validate}). Sans fourchette (tout
     * autre trajet, ou bateau d'avant le lot 3), elle reste nulle. Le retard d'un bateau parti passe
     * par l'annonce de retard, qui fixe la fourchette entière.
     */
    static java.time.LocalDate shiftedArrivalDateMax(AnnouncementEntity announcement, TripRescheduleRequest request) {
        java.time.LocalDate currentMax = announcement.getArrivalDateMax();
        if (currentMax == null || request.arrivalDate() == null) {
            return null;
        }
        java.time.LocalDate currentMin = announcement.getArrivalDate() != null
                ? announcement.getArrivalDate() : announcement.getDepartureDate();
        long width = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(currentMin, currentMax));
        java.time.LocalDate shifted = request.arrivalDate().plusDays(width);
        java.time.LocalDate ceiling = request.departureDate()
                .plusDays(ArrivalRules.maxDaysAfterDeparture(TransportMode.BOAT));
        return shifted.isAfter(ceiling) ? ceiling : shifted;
    }

    /**
     * Recale chaque colis sur les nouveaux horaires. Accepté : nouvelle limite de remise
     * (copiée sur le bid, que lisent le rappel H-2 et l'absence automatique) et rappel
     * H-2 réarmé. Remis : expiration du code de retrait recalculée. Les deux attendent la
     * réponse de l'expéditeur ; les demandes pas encore acceptées sont seulement prévenues.
     */
    private List<TripRescheduledEvent.Target> applyToBids(AnnouncementEntity announcement, UUID rescheduleId) {
        Set<BidStatus> touched = EnumSet.copyOf(TripRescheduleRules.DECISION_STATUSES);
        touched.addAll(TripRescheduleRules.INFORMED_STATUSES);
        List<TripRescheduledEvent.Target> targets = new ArrayList<>();
        for (BidEntity bid : bidRepository.findByAnnouncementIdAndStatusIn(announcement.getId(), List.copyOf(touched))) {
            boolean decisionRequired = TripRescheduleRules.DECISION_STATUSES.contains(bid.getStatus());
            if (bid.getStatus() == BidStatus.ACCEPTED) {
                bid.applyHandoverFrom(announcement);
                bid.setH2AlertSentAt(null);
            } else if (bid.getStatus() == BidStatus.HANDED_OVER && bid.getConfirmationCode() != null) {
                bid.setConfirmationCodeExpiry(ArrivalRules.pickupCodeExpiry(announcement));
            }
            if (decisionRequired) {
                bid.setPendingRescheduleId(rescheduleId);
            }
            bidRepository.save(bid);
            targets.add(new TripRescheduledEvent.Target(bid.getId(), bid.getSenderId(), decisionRequired));
        }
        return targets;
    }
}
