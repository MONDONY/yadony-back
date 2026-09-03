package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.dto.CalendarStatsResponse;
import com.yadony.api.matching.dto.InviteRequest;
import com.yadony.api.matching.dto.MatchingRequestDto;
import com.yadony.api.matching.dto.ProAnalyticsResponse;
import com.yadony.api.matching.dto.TravelerStatsDto;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/travelers")
public class TravelerStatsController {

    private final TravelerStatsService statsService;
    private final UserRepository userRepository;
    private final ProAnalyticsService analyticsService;
    private final AnnouncementRepository announcementRepository;
    private final MatchingService matchingService;
    private final PackageRequestRepository packageRequestRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final BidService bidService;
    private final AnnouncementService announcementService;

    public TravelerStatsController(
            TravelerStatsService statsService,
            UserRepository userRepository,
            ProAnalyticsService analyticsService,
            AnnouncementRepository announcementRepository,
            MatchingService matchingService,
            PackageRequestRepository packageRequestRepository,
            NotificationDispatcher notificationDispatcher,
            BidService bidService,
            AnnouncementService announcementService) {
        this.statsService = statsService;
        this.userRepository = userRepository;
        this.analyticsService = analyticsService;
        this.announcementRepository = announcementRepository;
        this.matchingService = matchingService;
        this.packageRequestRepository = packageRequestRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.bidService = bidService;
        this.announcementService = announcementService;
    }

    @GetMapping("/me/stats")
    public ResponseEntity<TravelerStatsDto> getMyStats() {
        String firebaseUid = requireFirebaseUid();

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Les statistiques sont réservées aux voyageurs PRO.");
        }

        return ResponseEntity.ok(statsService.computeStats(user));
    }

    @GetMapping("/me/analytics")
    public ResponseEntity<ProAnalyticsResponse> getMyAnalytics(
            @RequestParam(defaultValue = "month") String period) {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Analytics réservés aux voyageurs PRO.");
        }
        if (!List.of("month", "quarter", "year").contains(period)) {
            throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST, "invalid-period",
                    "Invalid period", "Période invalide. Valeurs acceptées: month, quarter, year.");
        }
        return ResponseEntity.ok(analyticsService.computeAnalytics(user, period));
    }

    @GetMapping("/me/calendar")
    public ResponseEntity<CalendarStatsResponse> getMyCalendar() {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Calendrier réservé aux voyageurs PRO.");
        }
        YearMonth current = YearMonth.now();
        LocalDateTime from = current.atDay(1).atStartOfDay();
        LocalDateTime to = current.atEndOfMonth().atTime(23, 59, 59);
        long activeTrips = announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.ACTIVE);
        long totalMonth = announcementRepository.countByTravelerIdAndCreatedAtBetween(user.getId(), from, to);
        return ResponseEntity.ok(new CalendarStatsResponse(activeTrips, totalMonth));
    }

    /**
     * @deprecated Remplacé par {@code GET /package-requests?matchingMyTrips=true},
     * qui apporte la pagination, la combinaison avec les autres filtres et la
     * déduplication par demande. Conservé le temps que les clients installés
     * migrent. Ne pas supprimer sans vérifier les versions d'app en circulation.
     */
    @Deprecated(since = "2026-07-22")
    @GetMapping("/me/matching-requests")
    public ResponseEntity<List<MatchingRequestDto>> getMatchingRequests() {
        String firebaseUid = requireFirebaseUid();
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER)) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "traveler-required",
                    "Traveler role required", "Réservé aux voyageurs.");
        }
        return ResponseEntity.ok(matchingService.findMatchingRequests(user.getId()));
    }

    @PostMapping("/me/invite")
    public ResponseEntity<Void> inviteSender(@Valid @RequestBody InviteRequest body) {
        String firebaseUid = requireFirebaseUid();
        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!traveler.getRoles().contains(Role.TRAVELER) || !traveler.isProAccount()) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Invitations réservées aux voyageurs PRO.");
        }

        AnnouncementEntity announcement = announcementRepository.findById(body.announcementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Announcement Not Found", "Annonce introuvable."));
        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "not-your-announcement",
                    "Forbidden", "Cette annonce ne vous appartient pas.");
        }

        PackageRequestEntity request = packageRequestRepository.findById(body.requestId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "request-not-found",
                        "Request Not Found", "Demande introuvable."));
        if (request.getStatus() != PackageRequestStatus.OPEN) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "request-not-open",
                    "Request Not Open", "Cette demande n'est plus disponible.");
        }

        var text = com.yadony.api.notifications.NotificationTexts.travelerInvite(
                buildTravelerName(traveler), announcement.getDepartureCity(), announcement.getArrivalCity());
        // Confidentialité — sollicitation directe d'un tiers, donc exactement ce qu'un
        // blocage doit faire taire. Rien n'est renvoyé au voyageur : le 200 est conservé
        // pour que le blocage reste indétectable côté émetteur.
        notificationDispatcher.notifyUnlessBlocked(
                request.getSenderId(),
                traveler.getId(),
                text.title(),
                text.body(),
                Map.of(
                        "type", "TRAVELER_INVITE",
                        "announcementId", body.announcementId().toString(),
                        "requestId", body.requestId().toString()
                )
        );

        return ResponseEntity.ok().build();
    }

    /** Délègue à {@link UserEntity#publicDisplayName()} : repli sur le username, pas « Un voyageur ». */
    private String buildTravelerName(UserEntity user) {
        return user.publicDisplayName();
    }

    @GetMapping("/me/bids")
    public ResponseEntity<Page<BidResponse>> getMyBids(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getTravelerBids(firebaseUid, status, tripId, q, page, size));
    }

    @GetMapping("/{travelerId}/announcements")
    public ResponseEntity<List<com.yadony.api.matching.dto.TravelerAnnouncementResponse>> travelerAnnouncements(
            @PathVariable UUID travelerId) {
        return ResponseEntity.ok(announcementService.getTravelerAnnouncements(currentFirebaseUidOrNull(), travelerId));
    }

    /**
     * Endpoint public : le viewer est facultatif, mais s'il est connecté son identité
     * sert à masquer les trajets d'un voyageur bloqué (dans un sens ou dans l'autre).
     */
    private String currentFirebaseUidOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getPrincipal() instanceof String s ? s : null;
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        return auth.getName();
    }
}
