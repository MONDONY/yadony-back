package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidCheckoutRequest;
import com.yadony.api.matching.dto.BidCheckoutResponse;
import com.yadony.api.matching.dto.BidQuoteRequest;
import com.yadony.api.matching.dto.BidQuoteResponse;
import com.yadony.api.matching.dto.BidRejectRequest;
import com.yadony.api.matching.dto.BidRequest;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.dto.ContactPhoneResponse;
import com.yadony.api.matching.dto.RefuseParcelRequest;
import com.yadony.api.payments.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
public class BidController {

    private final BidService bidService;
    private final BidCheckoutService bidCheckoutService;
    private final PaymentService paymentService;
    private final com.yadony.api.cancellation.CancellationService cancellationService;

    public BidController(BidService bidService,
                         BidCheckoutService bidCheckoutService,
                         PaymentService paymentService,
                         com.yadony.api.cancellation.CancellationService cancellationService) {
        this.bidService = bidService;
        this.bidCheckoutService = bidCheckoutService;
        this.paymentService = paymentService;
        this.cancellationService = cancellationService;
    }

    // ── Sender creates a bid via payment-first checkout ───────────────────────

    @PostMapping("/bids/checkout")
    public ResponseEntity<BidCheckoutResponse> checkout(
            @Valid @RequestBody BidCheckoutRequest request,
            HttpServletRequest httpRequest
    ) {
        String firebaseUid = requireFirebaseUid();
        BidCheckoutResponse response = bidCheckoutService.checkout(firebaseUid, request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── Sender confirms payment was authorized (safety net for missing webhook) ──

    @PostMapping("/bids/{bidId}/confirm-payment")
    public ResponseEntity<BidResponse> confirmPayment(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        bidService.assertSenderOwnsBid(bidId, firebaseUid);
        paymentService.confirmBidPayment(bidId);
        return ResponseEntity.ok(bidService.getBidById(bidId, firebaseUid));
    }

    // ── Sender creates a cash bid ─────────────────────────────────────────────

    @PostMapping("/announcements/{announcementId}/bids")
    public ResponseEntity<BidResponse> createBid(
            @PathVariable UUID announcementId,
            @Valid @RequestBody BidRequest request,
            HttpServletRequest httpRequest
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bidService.createBid(announcementId, firebaseUid, request, httpRequest));
    }

    // ── Traveler views bids on their announcement ─────────────────────────────

    @GetMapping("/announcements/{announcementId}/bids")
    public ResponseEntity<List<BidResponse>> getBidsForAnnouncement(@PathVariable UUID announcementId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getBidsForAnnouncement(announcementId, firebaseUid));
    }

    // ── Bid detail (traveler or sender) ──────────────────────────────────────

    @GetMapping("/bids/{bidId}")
    public ResponseEntity<BidResponse> getBidById(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getBidById(bidId, firebaseUid));
    }

    @GetMapping("/bids/me")
    public ResponseEntity<List<BidResponse>> getMyBids() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getMyBids(firebaseUid));
    }

    /**
     * Numéro de la contrepartie, demandé au moment où l'utilisateur veut appeler.
     * Les réponses de colis ne portent qu'un booléen {@code *PhoneAvailable} : le
     * numéro ne quitte le serveur que par cet appel, et chaque révélation est
     * journalisée.
     */
    @GetMapping("/bids/{bidId}/contact")
    public ResponseEntity<ContactPhoneResponse> getCounterpartyPhone(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getCounterpartyPhone(bidId, firebaseUid));
    }

    // ── Traveler accepts a bid ────────────────────────────────────────────────

    @PutMapping("/bids/{bidId}/accept")
    public ResponseEntity<BidResponse> acceptBid(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.acceptBid(bidId, firebaseUid));
    }

    // ── Traveler rejects a bid ────────────────────────────────────────────────

    @PutMapping("/bids/{bidId}/reject")
    public ResponseEntity<BidResponse> rejectBid(
            @PathVariable UUID bidId,
            @Valid @RequestBody(required = false) BidRejectRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.rejectBid(bidId, firebaseUid, request));
    }

    // ── Traveler confirms presence ────────────────────────────────────────────

    @PutMapping("/bids/{bidId}/confirm-presence")
    public ResponseEntity<BidResponse> confirmPresence(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.confirmPresence(bidId, firebaseUid));
    }

    @PutMapping("/bids/{bidId}/cancel")
    public ResponseEntity<BidResponse> cancelBid(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.cancelBid(bidId, firebaseUid));
    }

    // Annulation après remise du colis (HANDED_OVER) — expéditeur OU voyageur.
    // Ownership + verrou D3 (409) gérés dans CancellationService (package cancellation/).
    @PostMapping("/bids/{bidId}/cancel-after-handover")
    public ResponseEntity<BidResponse> cancelAfterHandover(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        cancellationService.cancelAfterHandover(firebaseUid, bidId);
        return ResponseEntity.ok(bidService.getBidAfterOwnMutation(bidId, firebaseUid));
    }

    // Story 9.4 — Voyageur refuse le colis lors de l'inspection
    @PostMapping("/bids/{bidId}/refuse-parcel")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<BidResponse> refuseParcel(@PathVariable UUID bidId,
                                                     @Valid @RequestBody RefuseParcelRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.refuseParcel(bidId, firebaseUid,
                request.reason(), request.refusalPhotoUrl()));
    }

    @DeleteMapping("/bids/{bidId}/me")
    public ResponseEntity<Void> hideBid(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        bidService.hideBidForSender(bidId, firebaseUid);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/bids/{bidId}/traveler")
    public ResponseEntity<Void> dismissBidAsTraveler(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        bidService.hideBidForTraveler(bidId, firebaseUid);
        return ResponseEntity.noContent().build();
    }

    // ── Devis : calcule net/commission/total avec promo éventuel ─────────────

    @PostMapping("/bids/quote")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<BidQuoteResponse> quote(
            @Valid @RequestBody BidQuoteRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.quote(firebaseUid, request));
    }

    @PostMapping("/bids/photos")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<java.util.Map<String, String>> uploadBidPhoto(
            @RequestParam("file") MultipartFile file) {
        String firebaseUid = requireFirebaseUid();
        String key = bidService.uploadBidPhoto(firebaseUid, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(java.util.Map.of("key", key));
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
