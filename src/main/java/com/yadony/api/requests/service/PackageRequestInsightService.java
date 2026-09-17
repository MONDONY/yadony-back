package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.requests.dto.PackageRequestInsightsResponse;
import com.yadony.api.requests.dto.PackageRequestInvitationResponse;
import com.yadony.api.requests.dto.PackageRequestResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestInvitationEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.PackageRequestInvitationSentEvent;
import com.yadony.api.requests.repository.PackageRequestInvitationRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce que l'expéditeur voit autour de sa demande : le nombre de consultations et les
 * voyageurs qu'il a invités. Séparé de {@link PackageRequestService} pour ne pas
 * alourdir son constructeur, déjà utilisé par de nombreux tests.
 */
@Service
public class PackageRequestInsightService {

    private static final Logger log = LoggerFactory.getLogger(PackageRequestInsightService.class);

    static final int MAX_INVITATIONS_PER_REQUEST = 10;

    private final PackageRequestRepository requestRepository;
    private final PackageRequestInvitationRepository invitationRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public PackageRequestInsightService(PackageRequestRepository requestRepository,
                                        PackageRequestInvitationRepository invitationRepository,
                                        AnnouncementRepository announcementRepository,
                                        UserRepository userRepository,
                                        AuditService auditService,
                                        ApplicationEventPublisher eventPublisher) {
        this.requestRepository = requestRepository;
        this.invitationRepository = invitationRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    public record InvitationResult(PackageRequestInvitationResponse invitation, boolean created) {}

    /**
     * Compte une consultation : appelant connecté, autre que l'expéditeur, sur une
     * demande encore en circulation. Hors transaction de lecture ({@code getById} est
     * readOnly, Postgres y refuserait l'UPDATE) et sans jamais faire échouer la lecture.
     */
    public void recordView(UUID viewerId, PackageRequestResponse viewed) {
        if (viewerId == null || viewerId.equals(viewed.senderId())) {
            return;
        }
        if (viewed.status() != PackageRequestStatus.OPEN && viewed.status() != PackageRequestStatus.NEGOTIATING) {
            return;
        }
        try {
            requestRepository.incrementViewCount(viewed.id());
        } catch (RuntimeException e) {
            log.warn("Compteur de vues non incrémenté pour la demande {}", viewed.id(), e);
        }
    }

    @Transactional(readOnly = true)
    public PackageRequestInsightsResponse getInsights(UUID callerId, UUID requestId) {
        PackageRequestEntity request = requireOwnedRequest(requestRepository.findById(requestId), callerId);
        var invited = invitationRepository.findByPackageRequestIdOrderByCreatedAtAsc(requestId).stream()
            .map(PackageRequestInvitationEntity::getAnnouncementId)
            .toList();
        return new PackageRequestInsightsResponse(request.getViewCount(), invited);
    }

    /**
     * Invite le voyageur d'un trajet. La demande est verrouillée : deux gestes
     * simultanés ne créent qu'une invitation (la contrainte unique ne sert que de filet).
     */
    @Transactional
    public InvitationResult invite(UUID callerId, UUID requestId, UUID announcementId) {
        PackageRequestEntity request = requireOwnedRequest(requestRepository.findByIdForUpdate(requestId), callerId);
        if (request.getStatus() != PackageRequestStatus.OPEN && request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-invitable");
        }

        var existing = invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId);
        if (existing.isPresent()) {
            return new InvitationResult(toResponse(existing.get()), false);
        }

        AnnouncementEntity trip = announcementRepository.findById(announcementId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "announcement/not-found"));
        if (trip.getTravelerId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invitation/own-trip");
        }
        if (trip.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invitation/trip-not-active");
        }
        if (!isOnCorridor(trip, request)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invitation/off-corridor");
        }
        if (invitationRepository.countByPackageRequestId(requestId) >= MAX_INVITATIONS_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invitation/limit-reached");
        }

        PackageRequestInvitationEntity saved = invitationRepository.save(
            new PackageRequestInvitationEntity(requestId, announcementId, trip.getTravelerId(), callerId));

        auditService.log("PACKAGE_REQUEST", requestId, "INVITATION_SENT", callerId,
            Map.of("announcementId", announcementId.toString(), "travelerId", trip.getTravelerId().toString()));

        String senderName = userRepository.findById(callerId)
            .map(UserEntity::publicDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        eventPublisher.publishEvent(new PackageRequestInvitationSentEvent(
            saved.getId(), requestId, announcementId, callerId, trip.getTravelerId(), senderName,
            request.getDepartureCity(), request.getArrivalCity()));

        return new InvitationResult(toResponse(saved), true);
    }

    private static PackageRequestInvitationResponse toResponse(PackageRequestInvitationEntity e) {
        return new PackageRequestInvitationResponse(e.getAnnouncementId(), e.getCreatedAt());
    }

    /**
     * Charge la demande et vérifie que l'appelant en est l'expéditeur. Un non-propriétaire
     * reçoit un 404, jamais un 403 : aligné sur {@code PackageRequestService.getById}, qui
     * masque déjà l'existence d'une demande à qui n'a pas à la voir.
     */
    private static PackageRequestEntity requireOwnedRequest(Optional<PackageRequestEntity> found, UUID callerId) {
        PackageRequestEntity request = found
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (!request.getSenderId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found");
        }
        return request;
    }

    /**
     * Le trajet du voyageur invité doit correspondre au corridor et à la fenêtre de dates
     * de la demande : même ville de départ et d'arrivée (insensible à la casse et aux
     * espaces superflus), départ dans {@code [desiredDate - tolerance, desiredDate + tolerance]}.
     */
    private static boolean isOnCorridor(AnnouncementEntity trip, PackageRequestEntity request) {
        return sameCity(trip.getDepartureCity(), request.getDepartureCity())
            && sameCity(trip.getArrivalCity(), request.getArrivalCity())
            && isWithinDateTolerance(trip.getDepartureDate(), request.getDesiredDate(), request.getDateToleranceDays());
    }

    private static boolean sameCity(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean isWithinDateTolerance(LocalDate tripDate, LocalDate desiredDate, Short toleranceDays) {
        if (tripDate == null || desiredDate == null) {
            return false;
        }
        int tolerance = toleranceDays == null ? 0 : toleranceDays;
        LocalDate earliest = desiredDate.minusDays(tolerance);
        LocalDate latest = desiredDate.plusDays(tolerance);
        return !tripDate.isBefore(earliest) && !tripDate.isAfter(latest);
    }
}
