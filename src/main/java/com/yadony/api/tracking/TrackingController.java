package com.yadony.api.tracking;

import com.yadony.api.tracking.dto.ConfirmCodeResponse;
import com.yadony.api.tracking.dto.ConfirmDeliveryRequest;
import com.yadony.api.tracking.dto.QrCodeResponse;
import com.yadony.api.tracking.dto.QrScanRequest;
import com.yadony.api.tracking.dto.TrackingEventResponse;
import com.yadony.api.tracking.dto.TrackingSearchResponse;
import com.yadony.api.tracking.dto.TripScanHistoryEntryDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tracking")
public class TrackingController {

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @GetMapping("/{bidId}/qr-code")
    public ResponseEntity<QrCodeResponse> getQrCode(
            @PathVariable UUID bidId,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.getQrCode(bidId, firebaseUid));
    }

    @GetMapping("/search")
    public ResponseEntity<TrackingSearchResponse> search(
            @RequestParam String number,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.searchByTrackingNumber(number, firebaseUid));
    }

    @PostMapping("/events")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<TrackingEventResponse> scan(
            @Valid @RequestBody QrScanRequest request,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(trackingService.processScan(request, firebaseUid));
    }

    @GetMapping("/{bidId}/events")
    public ResponseEntity<List<TrackingEventResponse>> getEvents(
            @PathVariable UUID bidId,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.getEvents(bidId, firebaseUid));
    }

    @GetMapping("/announcements/{announcementId}/events")
    public ResponseEntity<List<TripScanHistoryEntryDto>> getTripScanHistory(
            @PathVariable UUID announcementId,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.getTripScanHistory(announcementId, firebaseUid));
    }

    @GetMapping("/{bidId}/confirmation-code")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<ConfirmCodeResponse> getConfirmationCode(
            @PathVariable UUID bidId,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.getConfirmationCode(bidId, firebaseUid));
    }

    @PostMapping("/{bidId}/refresh-code")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<ConfirmCodeResponse> refreshCode(
            @PathVariable UUID bidId,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.refreshConfirmationCode(bidId, firebaseUid));
    }

    @PostMapping("/{bidId}/confirmation-code/public")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<ConfirmCodeResponse> setConfirmationCodePublicVisible(
            @PathVariable UUID bidId,
            @RequestParam(defaultValue = "true") boolean visible,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(
                trackingService.setConfirmationCodePublicVisible(bidId, firebaseUid, visible));
    }

    @PostMapping("/{bidId}/confirm-delivery")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<TrackingEventResponse> confirmDelivery(
            @PathVariable UUID bidId,
            @Valid @RequestBody ConfirmDeliveryRequest request,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.confirmDelivery(bidId, request, firebaseUid));
    }
}
