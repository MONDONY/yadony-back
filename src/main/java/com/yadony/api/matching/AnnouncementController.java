package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.PageResponse;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.dto.ArrivalInstructionsRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yadony.api.matching.dto.CorridorDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/announcements")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<AnnouncementSearchResponse>> searchAnnouncements(
            @RequestParam(required = false) String departureCity,
            @RequestParam(required = false) String arrivalCity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDateTo,
            @RequestParam(required = false) BigDecimal minAvailableKg,
            @RequestParam(required = false) BigDecimal maxAvailableKg,
            @RequestParam(required = false) BigDecimal maxPricePerKg,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) Boolean kiloProOnly,
            @RequestParam(required = false) Boolean weekendOnly,
            @RequestParam(required = false) Boolean urgent,
            @RequestParam(required = false) String transportMode,
            @RequestParam(required = false) Boolean kycVerifiedOnly,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(defaultValue = "date") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            Pageable pageable
    ) {
        Page<AnnouncementSearchResponse> page = announcementService.searchAnnouncements(
                departureCity, arrivalCity, departureDateFrom, departureDateTo,
                minAvailableKg, maxAvailableKg, maxPricePerKg, minRating, kiloProOnly, weekendOnly,
                transportMode, kycVerifiedOnly, contentType,
                userLat, userLng, radiusKm,
                sortBy, sortDir, pageable, currentFirebaseUidOrNull(), urgent);
        return ResponseEntity.ok(PageResponse.from(page));
    }

    @PostMapping
    public ResponseEntity<AnnouncementResponse> createAnnouncement(@Valid @RequestBody AnnouncementRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(announcementService.createAnnouncement(firebaseUid, request));
    }

    @GetMapping("/my/corridors")
    public ResponseEntity<List<CorridorDto>> getMyCorridors() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.getMyCorridors(firebaseUid));
    }

    @GetMapping("/my")
    public ResponseEntity<PageResponse<AnnouncementResponse>> getMyAnnouncements(
            @RequestParam(required = false) AnnouncementStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String departure,
            @RequestParam(required = false) String arrival,
            Pageable pageable) {
        String firebaseUid = requireFirebaseUid();
        Page<AnnouncementResponse> page = announcementService.getMyAnnouncements(
                firebaseUid, status, q, date, dateFrom, dateTo, departure, arrival, pageable);
        return ResponseEntity.ok(PageResponse.from(page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnnouncementDetailResponse> getAnnouncementDetail(@PathVariable UUID id) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.getAnnouncementDetail(id, firebaseUid));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AnnouncementDetailResponse> updateAnnouncement(
            @PathVariable UUID id,
            @Valid @RequestBody AnnouncementRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.updateAnnouncement(id, firebaseUid, request));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<AnnouncementDetailResponse> publishAnnouncement(@PathVariable UUID id) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.publishAnnouncement(id, firebaseUid));
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<AnnouncementDetailResponse> unpublishAnnouncement(@PathVariable UUID id) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.unpublishAnnouncement(id, firebaseUid));
    }

    @PostMapping("/{id}/mark-arrived")
    public ResponseEntity<AnnouncementDetailResponse> markArrived(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ArrivalInstructionsRequest request) {
        String firebaseUid = requireFirebaseUid();
        String instructions = request != null ? request.arrivalInstructions() : null;
        return ResponseEntity.ok(announcementService.markArrived(id, firebaseUid, instructions));
    }

    @PatchMapping("/{id}/arrival-instructions")
    public ResponseEntity<AnnouncementDetailResponse> updateArrivalInstructions(
            @PathVariable UUID id,
            @Valid @RequestBody ArrivalInstructionsRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(
                announcementService.updateArrivalInstructions(id, firebaseUid, request.arrivalInstructions()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable UUID id) {
        String firebaseUid = requireFirebaseUid();
        announcementService.deleteAnnouncement(id, firebaseUid);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the current user's Firebase UID, or {@code null} if the request is
     * unauthenticated. Search is publicly accessible, but when a user is logged in
     * we use their UID to filter out announcements from blocked travelers (both directions).
     */
    private String currentFirebaseUidOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof String s ? s : null;
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "unauthorized",
                    "Unauthorized",
                    "Un token Firebase valide est requis"
            );
        }
        return (String) auth.getPrincipal();
    }
}
