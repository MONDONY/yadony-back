package com.yadony.api.payments.supplement;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyInitiateRequest;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayerProvidersResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersRequest;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import com.yadony.api.payments.supplement.dto.SupplementOptionsResponse;
import com.yadony.api.payments.supplement.dto.SupplementQuoteRequest;
import com.yadony.api.payments.supplement.dto.SupplementQuoteResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Supplément de prix à la remise. Le voyageur du trajet demande, l'expéditeur du colis répond ;
 * la propriété du colis est vérifiée dans le service, en plus du rôle.
 */
@RestController
public class BidSupplementController {

    private final BidSupplementService service;
    private final BidSupplementPaymentFlow paymentFlow;
    private final BidSupplementMobileMoneyRail mobileMoney;

    public BidSupplementController(BidSupplementService service, BidSupplementPaymentFlow paymentFlow,
                                   BidSupplementMobileMoneyRail mobileMoney) {
        this.service = service;
        this.paymentFlow = paymentFlow;
        this.mobileMoney = mobileMoney;
    }

    @GetMapping("/bids/{bidId}/supplements/options")
    @PreAuthorize("hasRole('TRAVELER')")
    public SupplementOptionsResponse options(@PathVariable UUID bidId) {
        return service.options(bidId, firebaseUid());
    }

    @PostMapping("/bids/{bidId}/supplements/quote")
    @PreAuthorize("hasRole('TRAVELER')")
    public SupplementQuoteResponse quote(@PathVariable UUID bidId, @Valid @RequestBody SupplementQuoteRequest request) {
        return service.quote(bidId, firebaseUid(), request.netAmount());
    }

    @PostMapping("/bids/{bidId}/supplements")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<BidSupplementResponse> create(@PathVariable UUID bidId,
                                                        @Valid @RequestBody CreateSupplementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(bidId, firebaseUid(), request));
    }

    @DeleteMapping("/bids/{bidId}/supplements/{supplementId}")
    @PreAuthorize("hasRole('TRAVELER')")
    public BidSupplementResponse withdraw(@PathVariable UUID bidId, @PathVariable UUID supplementId) {
        return service.withdraw(bidId, supplementId, firebaseUid());
    }

    @PostMapping("/bids/{bidId}/supplements/{supplementId}/accept")
    @PreAuthorize("hasRole('SENDER')")
    public SupplementAcceptResponse accept(@PathVariable UUID bidId, @PathVariable UUID supplementId) {
        return paymentFlow.accept(bidId, supplementId, firebaseUid());
    }

    /**
     * Filet de l'app après la PaymentSheet (rail carte), comme {@code POST /bids/{id}/confirm-payment} :
     * relit le PaymentIntent chez Stripe et enregistre le paiement s'il a réussi, sans attendre le
     * webhook. Idempotent ; vue de l'expéditeur (brut seulement).
     */
    @PostMapping("/bids/{bidId}/supplements/{supplementId}/confirm-payment")
    @PreAuthorize("hasRole('SENDER')")
    public BidSupplementResponse confirmPayment(@PathVariable UUID bidId, @PathVariable UUID supplementId) {
        return paymentFlow.confirmPayment(bidId, supplementId, firebaseUid());
    }

    @PostMapping("/bids/{bidId}/supplements/{supplementId}/decline")
    @PreAuthorize("hasRole('SENDER')")
    public BidSupplementResponse decline(@PathVariable UUID bidId, @PathVariable UUID supplementId) {
        return service.decline(bidId, supplementId, firebaseUid());
    }

    /**
     * Rail mobile money : l'expéditeur déclenche le push PIN (ou la redirection Wave) du brut du
     * supplément. Corps optionnel : autre numéro payeur et opérateur choisi (même contrat que
     * {@code POST /bids/{id}/mobile-money/initiate}). Idempotent : un dépôt en vol est relu.
     */
    @PostMapping("/bids/{bidId}/supplements/{supplementId}/mobile-money/initiate")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<MobileMoneyPaymentStatusResponse> initiateMobileMoney(
            @PathVariable UUID bidId, @PathVariable UUID supplementId,
            @RequestBody(required = false) MobileMoneyInitiateRequest body) {
        String phone = body == null ? null : body.phoneNumber();
        String provider = body == null ? null : body.provider();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mobileMoney.initiate(bidId, supplementId, firebaseUid(), phone, provider));
    }

    /** Réseaux avec lesquels l'expéditeur peut payer le supplément. Corps optionnel : autre numéro payeur. */
    @PostMapping("/bids/{bidId}/supplements/{supplementId}/mobile-money/providers")
    @PreAuthorize("hasRole('SENDER')")
    public MobileMoneyPayerProvidersResponse mobileMoneyProviders(
            @PathVariable UUID bidId, @PathVariable UUID supplementId,
            @RequestBody(required = false) MobileMoneyProvidersRequest body) {
        return mobileMoney.providersForPayer(bidId, supplementId, firebaseUid(),
                body == null ? null : body.phoneNumber());
    }

    /** Statut du paiement mobile money du supplément, pour l'expéditeur et le voyageur. */
    @GetMapping("/bids/{bidId}/supplements/{supplementId}/mobile-money/status")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public MobileMoneyPaymentStatusResponse mobileMoneyStatus(@PathVariable UUID bidId,
                                                              @PathVariable UUID supplementId) {
        return mobileMoney.status(bidId, supplementId, firebaseUid());
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
