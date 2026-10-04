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

import java.math.BigDecimal;
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

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public TripRescheduleResponse reschedule(UUID announcementId, String firebaseUid, TripRescheduleRequest request) {
        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Announcement Not Found", "Annonce introuvable"));
        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à reporter ce trajet");
        }
        assertReschedulable(announcement);
        assertNewSchedule(announcement, request);

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
        announcement.setArrivalTime(request.arrivalTime());
        announcement.setDepartureAt(AnnouncementService.deriveDepartureAt(
                request.departureDate(), request.departureTime(), announcement.getTimezone()));
        announcement.setHandoverDeadline(request.handoverDeadline());
        announcement.setRescheduleCount(announcement.getRescheduleCount() + 1);
        if (announcement.getStatus() == AnnouncementStatus.IN_PROGRESS) {
            // Le planificateur l'avait fait partir à l'ancienne heure : le trajet repart
            // sur le marché, complet s'il ne reste plus de place.
            boolean full = announcement.getCapacityUnit() != CapacityUnit.KG_FREE
                    && announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0;
            announcement.setStatus(full ? AnnouncementStatus.FULL : AnnouncementStatus.ACTIVE);
        }
        announcementRepository.save(announcement);

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

    private void assertReschedulable(AnnouncementEntity announcement) {
        if (!RESCHEDULABLE.contains(announcement.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "reschedule-invalid-status",
                    "Reschedule Impossible",
                    "Seul un trajet publié et pas encore terminé peut être reporté");
        }
        if (bidRepository.existsByAnnouncementIdAndStatusIn(announcement.getId(),
                List.copyOf(TripRescheduleRules.BLOCKING_STATUSES))) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "reschedule-in-transit",
                    "Reschedule Impossible",
                    "Un colis est déjà en route : le trajet ne peut plus être reporté");
        }
        if (announcement.getRescheduleCount() >= TripRescheduleRules.MAX_RESCHEDULES) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "reschedule-limit-reached",
                    "Reschedule Limit Reached",
                    "Ce trajet a déjà été reporté " + TripRescheduleRules.MAX_RESCHEDULES
                            + " fois. Annulez-le et publiez un nouveau trajet.");
        }
    }

    private void assertNewSchedule(AnnouncementEntity announcement, TripRescheduleRequest request) {
        if (request.departureDate().equals(announcement.getDepartureDate())
                && Objects.equals(request.departureTime(), announcement.getDepartureTime())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "reschedule-same-date",
                    "Same Date", "Choisissez une date ou une heure de départ différente de l'actuelle");
        }
        OffsetDateTime departureAt = AnnouncementService.deriveDepartureAt(
                request.departureDate(), request.departureTime(), announcement.getTimezone());
        if (departureAt != null && !departureAt.isAfter(OffsetDateTime.now())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-departure-date",
                    "Invalid Departure Date", "La nouvelle date de départ doit être dans le futur");
        }
        AnnouncementService.validateHandoverDeadline(request.handoverDeadline(),
                request.departureDate(), request.departureTime());
        // Une limite déjà passée ouvrirait le signalement d'absence dès le report.
        if (!request.handoverDeadline().isAfter(TripRescheduleRules.nowAt(announcement))) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "handover-deadline-past",
                    "Handover Deadline Past", "La date limite de remise doit être dans le futur");
        }
        ArrivalRules.validate(request.departureDate(), request.departureTime(),
                request.arrivalDate(), request.arrivalTime());
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
