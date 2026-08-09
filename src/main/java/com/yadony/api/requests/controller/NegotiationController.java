package com.yadony.api.requests.controller;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.requests.dto.*;
import com.yadony.api.requests.service.NegotiationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/negotiations")
public class NegotiationController {

    private final NegotiationService service;
    private final com.yadony.api.payments.PaymentService paymentService;
    private final UserRepository userRepository;

    public NegotiationController(NegotiationService service,
                                 com.yadony.api.payments.PaymentService paymentService,
                                 UserRepository userRepository) {
        this.service = service;
        this.paymentService = paymentService;
        this.userRepository = userRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<NegotiationThreadResponse> start(@RequestBody @Valid NegotiationStartRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.start(requireUserId(), req));
    }

    @GetMapping("/me")
    public List<NegotiationThreadResponse> findMine() {
        return service.listMine(requireUserId());
    }

    @GetMapping("/{id}")
    public NegotiationThreadResponse getById(@PathVariable UUID id) {
        return service.getById(requireUserId(), id);
    }

    @PostMapping("/{id}/counter")
    public NegotiationThreadResponse counter(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationCounterRequest req
    ) {
        return service.counter(requireUserId(), id, req);
    }

    @PostMapping("/{id}/accept")
    public NegotiationThreadResponse accept(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationAcceptRequest req
    ) {
        return service.accept(requireUserId(), id, req);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationRejectRequest req
    ) {
        service.reject(requireUserId(), id, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Either participant (sender or traveler) ends the negotiation before payment.
     * Allowed while OPEN, AWAITING_TRIP or AWAITING_PAYMENT.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER')")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid NegotiationRejectRequest req) {
        service.cancelNegotiation(requireUserId(), id, req == null ? null : req.reason());
        return ResponseEntity.noContent().build();
    }

    /** Traveler links a trip (existing announcement) to an AWAITING_TRIP thread. */
    @PostMapping("/{id}/submit-trip")
    @PreAuthorize("hasRole('TRAVELER')")
    public NegotiationThreadResponse submitTrip(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationSubmitTripRequest req
    ) {
        return service.submitTrip(requireUserId(), id, req);
    }

    /**
     * Traveler creates a brand-new "dedicated trip" announcement to satisfy
     * an AWAITING_TRIP thread (used when no existing trip matches). The trip
     * is private (excluded from public search) and the thread transitions to
     * AWAITING_PAYMENT atomically.
     */
    @PostMapping("/{id}/create-dedicated-trip")
    @PreAuthorize("hasRole('TRAVELER')")
    public NegotiationThreadResponse createDedicatedTrip(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationCreateDedicatedTripRequest req
    ) {
        return service.createDedicatedTrip(requireUserId(), id, req);
    }

    /**
     * Devis transparent avant paiement : net voyageur, commission Yadony (taux et
     * montant), total, et prévisualisation d'un code promo optionnel. Sender-only.
     */
    @GetMapping("/{id}/quote")
    @PreAuthorize("hasRole('SENDER')")
    public NegotiationQuoteResponse quote(
            @PathVariable UUID id,
            @RequestParam(required = false) String promoCode) {
        return service.quote(requireUserId(), id, promoCode);
    }

    /** Sender confirms payment for an AWAITING_PAYMENT thread → finalize as ACCEPTED. */
    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasRole('SENDER')")
    public NegotiationThreadResponse checkout(
            @PathVariable UUID id,
            @RequestBody @Valid NegotiationCheckoutRequest req
    ) {
        return service.checkout(requireUserId(), id, req.paymentIntentId(), req.paymentMethod());
    }

    /**
     * Sender refuses the linked trip — thread moves back to AWAITING_TRIP.
     * Only the sender of the package_request can call this endpoint.
     */
    @PostMapping("/{id}/refuse-trip")
    @PreAuthorize("hasRole('SENDER')")
    public NegotiationThreadResponse refuseTrip(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid NegotiationRefuseTripRequest req) {
        return service.refuseTrip(requireUserId(), id, req != null ? req.reason() : null);
    }

    /**
     * Sender initiates the Stripe escrow payment for an AWAITING_PAYMENT thread.
     * Returns the Stripe clientSecret to confirm via the Flutter SDK.
     * The thread is finalized to ACCEPTED only when the webhook fires
     * payment_intent.amount_capturable_updated (escrow active).
     */
    @PostMapping("/{id}/initiate-payment")
    @PreAuthorize("hasRole('SENDER')")
    public com.yadony.api.payments.dto.PaymentResponse initiatePayment(
            @PathVariable UUID id,
            @RequestParam(required = false) String promoCode) {
        UUID senderId = requireUserId();
        var thread = service.getById(senderId, id);
        // Defensive: only allowed if thread status = AWAITING_PAYMENT
        if (thread.status() != com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "thread/not-awaiting-payment");
        }
        // Auto-appliqué : le code a été saisi par l'expéditeur à la publication de
        // sa demande (étape budget) et porté sur le thread dès sa création — jamais
        // resaisi ici. Le param reste un override défensif (tests, cas futurs).
        String code = promoCode != null ? promoCode : thread.promoCode();
        var response = paymentService.createNegotiationEscrow(
            id, senderId, thread.travelerId(), thread.currentPriceEur(), code, thread.currency());
        // Persiste le taux réellement appliqué pour que le rachat (redeem, décompte
        // per-user-limit) puisse se raccrocher au bid_id une fois matérialisé après
        // paiement confirmé (ThreadAcceptedBidListener) — createNegotiationEscrow ne
        // peut pas le faire lui-même, aucun bid n'existe encore à cet instant.
        if (response.isPromoApplied()) {
            service.recordAppliedPromo(id, code, response.getCommissionRate());
        } else if (thread.promoCode() != null) {
            // Le code portait un promoCode mais createNegotiationEscrow est retombé sur le
            // tarif de base (code expiré, limite atteinte…) : nettoyer l'état pour que
            // ThreadAcceptedBidListener ne tente pas un rachat sur un code jamais appliqué.
            service.recordAppliedPromo(id, null, null);
        }
        return response;
    }

    /**
     * Traveler opens the remaining (surplus) capacity of a DEDICATED trip to the
     * public, once the negotiating sender has paid (thread ACCEPTED). The path
     * param is the dedicated announcement id, not a thread id.
     */
    @PostMapping("/trip/{announcementId}/open-surplus")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<Void> openSurplus(
            @PathVariable UUID announcementId,
            @RequestBody @Valid OpenSurplusRequest req
    ) {
        service.openSurplus(requireUserId(), announcementId, req.surplusKg(), req.pricePerKg());
        return ResponseEntity.noContent().build();
    }

    /**
     * The waiting party nudges the party who must act (traveler or sender)
     * to remind them to respond. Eligibility mirrors {@code canNudge} on the
     * response so the button and the endpoint agree.
     */
    @PostMapping("/{id}/nudge")
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER')")
    public NegotiationThreadResponse nudge(@PathVariable UUID id) {
        return service.nudge(requireUserId(), id);
    }

    // ─── Auth helper ─────────────────────────────────────────────────────────────

    private UUID requireUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis"
            );
        }
        String firebaseUid = (String) auth.getPrincipal();
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }
}
