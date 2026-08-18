package com.yadony.api.cancellation;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.CancellationRequest;
import com.yadony.api.cancellation.dto.CancellationResponse;
import com.yadony.api.cancellation.dto.RematchSuggestionDto;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/cancellations")
public class CancellationController {

    private final CancellationService cancellationService;
    private final UserRepository userRepository;

    public CancellationController(CancellationService cancellationService,
                                   UserRepository userRepository) {
        this.cancellationService = cancellationService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<CancellationResponse> cancelTrip(
            @Valid @RequestBody CancellationRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        CancellationResponse response = cancellationService.cancelTrip(firebaseUid, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{cancellationId}/rematch-suggestions")
    public ResponseEntity<List<RematchSuggestionDto>> getRematchSuggestions(
            @PathVariable UUID cancellationId
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(cancellationService.getRematchSuggestions(cancellationId, firebaseUid));
    }

    @PostMapping("/bids/{bidId}/report-noshow")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<Void> reportNoShow(@PathVariable UUID bidId) {
        UUID travelerId = resolveUserId();
        cancellationService.reportSenderNoShow(bidId, travelerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/confirm-noshow")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DISPUTE_RESOLVE')")
    public ResponseEntity<Void> confirmNoShow(@PathVariable UUID bidId) {
        cancellationService.confirmSenderNoShow(bidId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/confirm-noshow-self")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> confirmNoShowSelf(@PathVariable UUID bidId) {
        UUID senderId = resolveUserId();
        cancellationService.confirmSenderNoShow(bidId, senderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/contest-noshow")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> contestNoShow(@PathVariable UUID bidId) {
        UUID senderId = resolveUserId();
        cancellationService.contestSenderNoShow(bidId, senderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/report-traveler-noshow")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> reportTravelerNoShow(@PathVariable UUID bidId) {
        UUID senderId = resolveUserId();
        cancellationService.reportTravelerNoShow(bidId, senderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/report-delivery-noshow")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<Void> reportDeliveryNoShow(@PathVariable UUID bidId) {
        UUID travelerId = resolveUserId();
        cancellationService.reportDeliveryNoShow(bidId, travelerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/report-traveler-delivery-noshow")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> reportTravelerDeliveryNoShow(@PathVariable UUID bidId) {
        UUID senderId = resolveUserId();
        cancellationService.reportTravelerDeliveryNoShow(bidId, senderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bids/{bidId}/contest-delivery-noshow")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<Void> contestDeliveryNoShow(@PathVariable UUID bidId) {
        UUID callerId = resolveUserId();
        cancellationService.contestDeliveryNoShow(bidId, callerId);
        return ResponseEntity.ok().build();
    }

    // Le voyageur confirme le retour du colis (annulation après remise) en saisissant
    // le code de retour détenu par l'expéditeur.
    @PostMapping("/bids/{bidId}/confirm-return")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<com.yadony.api.cancellation.dto.ReturnCodeResponse> confirmReturn(
            @PathVariable UUID bidId,
            @Valid @RequestBody com.yadony.api.cancellation.dto.ConfirmReturnRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(
                cancellationService.confirmReturn(firebaseUid, bidId, request.returnCode()));
    }

    // L'expéditeur consulte son code de retour + l'état du retour.
    @GetMapping("/bids/{bidId}/return-code")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<com.yadony.api.cancellation.dto.ReturnCodeResponse> getReturnCode(
            @PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(cancellationService.getReturnCode(firebaseUid, bidId));
    }

    private UUID resolveUserId() {
        String firebaseUid = requireFirebaseUid();
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
