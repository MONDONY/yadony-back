package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidNegotiationCounterRequest;
import com.yadony.api.matching.dto.BidNegotiationResponse;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidNegotiationSummaryResponse;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Fil de négociation du prix d'un trajet.
 *
 * <p>Seule l'ouverture du fil exige le rôle SENDER : une fois le fil ouvert, les deux
 * parties agissent dessus, et l'appartenance à la discussion est vérifiée dans
 * {@link BidNegotiationService} (403 sinon), pas par un rôle.
 */
@RestController
public class BidNegotiationController {

    private final BidNegotiationService negotiationService;

    public BidNegotiationController(BidNegotiationService negotiationService) {
        this.negotiationService = negotiationService;
    }

    @PostMapping("/announcements/{announcementId}/bids/negotiation")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<BidNegotiationResponse> propose(
            @PathVariable UUID announcementId,
            @Valid @RequestBody BidNegotiationStartRequest request,
            HttpServletRequest httpRequest) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(negotiationService.propose(announcementId, firebaseUid, request, httpRequest));
    }

    @PostMapping("/bids/{bidId}/negotiation/counter")
    public ResponseEntity<BidNegotiationResponse> counter(
            @PathVariable UUID bidId,
            @Valid @RequestBody BidNegotiationCounterRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(negotiationService.counter(bidId, firebaseUid, request));
    }

    @PostMapping("/bids/{bidId}/negotiation/accept")
    public ResponseEntity<BidNegotiationResponse> accept(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(negotiationService.accept(bidId, firebaseUid));
    }

    @PostMapping("/bids/{bidId}/negotiation/reject")
    public ResponseEntity<BidNegotiationResponse> reject(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(negotiationService.reject(bidId, firebaseUid));
    }

    @PostMapping("/bids/{bidId}/negotiation/cancel")
    public ResponseEntity<BidNegotiationResponse> cancel(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(negotiationService.cancel(bidId, firebaseUid));
    }

    @PostMapping("/bids/{bidId}/negotiation/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        negotiationService.markRead(bidId, firebaseUid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bids/{bidId}/negotiation")
    public ResponseEntity<BidNegotiationResponse> thread(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(negotiationService.thread(bidId, firebaseUid));
    }

    @GetMapping("/bids/negotiations/me")
    public ResponseEntity<List<BidNegotiationSummaryResponse>> myNegotiations() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(negotiationService.myNegotiations(firebaseUid));
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
