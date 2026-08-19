package com.yadony.api.requests.controller;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.requests.dto.*;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.service.PackageRequestService;
import com.yadony.api.requests.service.PriceEstimationService;
import com.yadony.api.requests.specification.PackageRequestSpecifications;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/package-requests")
public class PackageRequestController {

    private final PackageRequestService service;
    private final PriceEstimationService estimationService;
    private final com.yadony.api.requests.service.NegotiationService negotiationService;
    private final com.yadony.api.requests.service.PackageRequestReportService reportService;
    private final UserRepository userRepository;
    private final PlatformSettingsService settings;

    public PackageRequestController(PackageRequestService service,
                                    PriceEstimationService estimationService,
                                    com.yadony.api.requests.service.NegotiationService negotiationService,
                                    com.yadony.api.requests.service.PackageRequestReportService reportService,
                                    UserRepository userRepository,
                                    PlatformSettingsService settings) {
        this.service = service;
        this.estimationService = estimationService;
        this.negotiationService = negotiationService;
        this.reportService = reportService;
        this.userRepository = userRepository;
        this.settings = settings;
    }

    @PostMapping
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<PackageRequestResponse> create(@RequestBody @Valid PackageRequestCreateRequest req) {
        UUID userId = requireUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, req));
    }

    /**
     * Devis transparent pendant que l'expéditeur fixe son budget (étape 3) :
     * commission Yadony (taux + montant) et prévisualisation d'un code promo
     * optionnel, avant même qu'un voyageur/thread existe.
     */
    @GetMapping("/quote")
    @PreAuthorize("hasRole('SENDER')")
    public NegotiationQuoteResponse quote(
            @RequestParam BigDecimal budgetEur,
            @RequestParam(required = false) String promoCode) {
        return service.quote(requireUserId(), budgetEur, promoCode);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('SENDER')")
    public Page<PackageRequestResponse> findMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.findMine(requireUserId(), PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public PackageRequestResponse getById(@PathVariable UUID id) {
        return service.getById(requireUserId(), id);
    }

    /** Sender edits a request while no agreement is reached (OPEN/NEGOTIATING). */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestResponse update(
            @PathVariable UUID id,
            @RequestBody @Valid PackageRequestCreateRequest req
    ) {
        return service.update(requireUserId(), id, req);
    }

    /** Publie un brouillon (DRAFT → OPEN) après avoir rejoué tous les contrôles. */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestResponse publish(@PathVariable UUID id) {
        return service.publish(requireUserId(), id);
    }

    /** Retire une demande de la circulation sans l'annuler (OPEN → DRAFT). */
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestResponse unpublish(@PathVariable UUID id) {
        return service.unpublish(requireUserId(), id);
    }

    @GetMapping("/{id}/threads")
    @PreAuthorize("hasRole('SENDER')")
    public java.util.List<com.yadony.api.requests.dto.NegotiationThreadResponse> listThreads(@PathVariable UUID id) {
        return negotiationService.listForRequest(requireUserId(), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        service.cancel(requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete-details")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestResponse completeDetails(
            @PathVariable UUID id,
            @RequestBody @Valid PackageRequestCompleteDetailsRequest req,
            HttpServletRequest httpRequest
    ) {
        String clientIp = extractClientIp(httpRequest);
        return service.completeDetails(requireUserId(), id, req, clientIp);
    }

    @GetMapping
    @PreAuthorize("hasRole('TRAVELER')")
    public Page<PackageRequestSearchResponse> search(
            @RequestParam(required = false) String departure,
            @RequestParam(required = false) String arrival,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) BigDecimal maxWeight,
            @RequestParam(required = false) ParcelSize parcelSize,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) Boolean urgent,
            @RequestParam(required = false) Boolean matchingMyTrips,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Specification<PackageRequestEntity> spec = Specification
                .where(PackageRequestSpecifications.openOnly())
                .and(PackageRequestSpecifications.corridor(departure, arrival))
                .and(PackageRequestSpecifications.dateRange(dateFrom, dateTo))
                .and(PackageRequestSpecifications.maxWeight(maxWeight))
                .and(PackageRequestSpecifications.parcelSize(parcelSize));
        if (Boolean.TRUE.equals(urgent)) {
            spec = spec.and(PackageRequestSpecifications.urgent(settings.urgencyThresholdDays()));
        }
        UUID callerId = requireUserId();
        Pageable pageable = PageRequest.of(page, size);
        // Le filtre « mes trajets » prime sur la recherche géographique : les deux
        // trient différemment (score vs distance) et ne se composent pas.
        if (Boolean.TRUE.equals(matchingMyTrips)) {
            return service.searchMatchingMyTrips(spec, pageable, callerId);
        }
        if (lat != null && lng != null) {
            double radius = radiusKm != null ? radiusKm : 50.0;
            return service.searchNearMe(spec, pageable, lat, lng, radius, callerId);
        }
        return service.search(spec, pageable, callerId);
    }

    /** Signale une demande (modération). Tout utilisateur authentifié, sauf le propriétaire. */
    @PostMapping("/{id}/report")
    public ResponseEntity<Void> report(
            @PathVariable UUID id,
            @RequestBody @Valid PackageRequestReportRequest req
    ) {
        reportService.report(requireUserId(), id, req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/estimate")
    public PriceEstimateResponse estimate(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam BigDecimal weight,
            @RequestParam(defaultValue = "EUR") String currency
    ) {
        return estimationService.estimate(from, to, weight, currency);
    }

    // ─── Auth helpers ────────────────────────────────────────────────────────────

    private UUID requireUserId() {
        String firebaseUid = requireFirebaseUid();
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis"
            );
        }
        return (String) auth.getPrincipal();
    }

    /**
     * Project rule: use the LAST X-Forwarded-For element (added by trusted proxy),
     * not the first — clients can spoof the first element.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
