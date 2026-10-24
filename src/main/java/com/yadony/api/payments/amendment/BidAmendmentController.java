package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.dto.AmendmentOptionsResponse;
import com.yadony.api.payments.amendment.dto.AmendmentQuoteRequest;
import com.yadony.api.payments.amendment.dto.AmendmentQuoteResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Modification d'un colis accepté. L'expéditeur demande (options, devis, création) ou retire sa demande ; le
 * voyageur refuse, accepte ou constate à la remise que les kilos ajoutés ne sont pas là. La propriété du colis est
 * vérifiée dans le service, en plus du rôle. Drapeau éteint : création, acceptation et paiement refusés en 422
 * {@code amendment-disabled} ; l'annulation, le refus et « non apportée » restent ouverts (ils rendent l'argent ou
 * les kilos).
 *
 * <p>Les routes de l'autorisation carte ({@code confirm-authorization}) et du mobile money arrivent avec leurs rails.
 */
@RestController
@RequestMapping("/bids/{bidId}/amendments")
public class BidAmendmentController {

    private final BidAmendmentService service;
    private final BidAmendmentPaymentFlow paymentFlow;

    public BidAmendmentController(BidAmendmentService service, BidAmendmentPaymentFlow paymentFlow) {
        this.service = service;
        this.paymentFlow = paymentFlow;
    }

    // ── Expéditeur ───────────────────────────────────────────────────────────

    @GetMapping("/options")
    @PreAuthorize("hasRole('SENDER')")
    public AmendmentOptionsResponse options(@PathVariable UUID bidId) {
        return service.options(bidId, firebaseUid());
    }

    @PostMapping("/quote")
    @PreAuthorize("hasRole('SENDER')")
    public AmendmentQuoteResponse quote(@PathVariable UUID bidId, @Valid @RequestBody AmendmentQuoteRequest request) {
        return service.quote(bidId, firebaseUid(), request);
    }

    @PostMapping
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<AmendmentCreateResponse> create(@PathVariable UUID bidId,
                                                          @Valid @RequestBody CreateAmendmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentFlow.create(bidId, firebaseUid(), request));
    }

    /** L'expéditeur retire sa demande tant que le voyageur ne l'a pas acquise. */
    @DeleteMapping("/{amendmentId}")
    @PreAuthorize("hasRole('SENDER')")
    public BidAmendmentResponse cancel(@PathVariable UUID bidId, @PathVariable UUID amendmentId) {
        return service.cancel(bidId, amendmentId, firebaseUid());
    }

    // ── Voyageur ─────────────────────────────────────────────────────────────

    @PostMapping("/{amendmentId}/decline")
    @PreAuthorize("hasRole('TRAVELER')")
    public BidAmendmentResponse decline(@PathVariable UUID bidId, @PathVariable UUID amendmentId) {
        return service.decline(bidId, amendmentId, firebaseUid());
    }

    /**
     * Corps optionnel, rail espèces seulement : source de la commission ({@code commissionSource}) et portefeuille
     * d'une autre devise ({@code fundingCurrency}).
     */
    @PostMapping("/{amendmentId}/accept")
    @PreAuthorize("hasRole('TRAVELER')")
    public AmendmentAcceptResponse accept(@PathVariable UUID bidId, @PathVariable UUID amendmentId,
                                          @Valid @RequestBody(required = false) AmendmentAcceptRequest request) {
        return paymentFlow.accept(bidId, amendmentId, firebaseUid(),
                request == null ? new AmendmentAcceptRequest(null, null) : request);
    }

    /**
     * Rail espèces : après la 3DS de la carte commission ({@code commissionClientSecret} de l'acceptation), relit la
     * PaymentIntent et rend la modification acquise. Idempotent ; vue du voyageur.
     */
    @PostMapping("/{amendmentId}/commission/confirm")
    @PreAuthorize("hasRole('TRAVELER')")
    public BidAmendmentResponse confirmCommission(@PathVariable UUID bidId, @PathVariable UUID amendmentId) {
        return paymentFlow.confirmCommission(bidId, amendmentId, firebaseUid());
    }

    /** À la remise, les kilos ajoutés ne sont pas là : la modification acquise est annulée et remboursée. */
    @PostMapping("/{amendmentId}/not-brought")
    @PreAuthorize("hasRole('TRAVELER')")
    public BidAmendmentResponse notBrought(@PathVariable UUID bidId, @PathVariable UUID amendmentId) {
        return service.notBrought(bidId, amendmentId, firebaseUid());
    }

    private static String firebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                    "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
