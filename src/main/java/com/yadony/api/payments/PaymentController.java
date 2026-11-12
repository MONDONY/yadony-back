package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeWebhookIngestService;
import com.yadony.api.common.stripe.StripeWebhookSource;
import com.yadony.api.payments.dto.ConnectAccountResponse;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.EphemeralKeyRequest;
import com.yadony.api.payments.dto.EphemeralKeyResponse;
import com.yadony.api.payments.dto.OnboardingLinkResponse;
import com.yadony.api.payments.dto.PaymentMethodResponse;
import com.yadony.api.payments.dto.PaymentResponse;
import com.yadony.api.payments.dto.UpdateSavePaymentMethodRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final StripeWebhookIngestService ingestService;

    public PaymentController(PaymentService paymentService,
                             StripeWebhookIngestService ingestService) {
        this.paymentService = paymentService;
        this.ingestService = ingestService;
    }

    // Story 6.2 — Lire l'état du compte Stripe Connect (lecture seule depuis la DB)
    // SENDER autorisé : utilisé pendant le flow "Devenir voyageur" avant activation du rôle
    @GetMapping("/connect/account")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<ConnectAccountResponse> getConnectAccount() {
        return ResponseEntity.ok(paymentService.getConnectAccountStatus(requireFirebaseUid()));
    }

    // Story 6.2 — Créer un compte Stripe Express pour le voyageur
    // SENDER autorisé : l'onboarding Stripe précède l'activation du rôle TRAVELER
    @PostMapping("/connect/account")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<ConnectAccountResponse> createConnectAccount() {
        return ResponseEntity.ok(paymentService.createConnectAccount(requireFirebaseUid()));
    }

    // Story 6.2 — Générer le lien d'onboarding Stripe (WebView)
    // SENDER autorisé : l'onboarding Stripe précède l'activation du rôle TRAVELER
    @PostMapping("/connect/onboarding-link")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<OnboardingLinkResponse> createOnboardingLink() {
        return ResponseEntity.ok(paymentService.createOnboardingLink(requireFirebaseUid()));
    }

    // Re-pulls the Stripe account state and syncs stripe_onboarded. Recovery path when the
    // account.updated webhook was missed (local dev without Stripe CLI, transient outage).
    // SENDER autorisé : utilisé pour vérifier l'état après retour du WebView Stripe
    @PostMapping("/connect/refresh")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<ConnectAccountResponse> refreshConnectAccount() {
        return ResponseEntity.ok(paymentService.refreshConnectAccount(requireFirebaseUid()));
    }

    // Story 6.3 — Créer un PaymentIntent en escrow pour un bid accepté
    @PostMapping
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<PaymentResponse> createEscrow(
            @Valid @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.ok(paymentService.createEscrow(request, requireFirebaseUid()));
    }

    // Statut paiement pour un bid — expéditeur ou voyageur (ownership vérifiée dans le service)
    @GetMapping("/bid/{bidId}")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<PaymentResponse> getPaymentForBid(@PathVariable UUID bidId) {
        String callerUid = requireFirebaseUid();
        return paymentService.getPaymentStatusForBid(bidId, callerUid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // YadonyPaymentSheet — cartes enregistrées de l'utilisateur courant (jamais bloquant : [] si aucune)
    @GetMapping("/me/payment-methods")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<List<PaymentMethodResponse>> listMyPaymentMethods() {
        return ResponseEntity.ok(paymentService.listSavedPaymentMethods(requireFirebaseUid()));
    }

    // PaymentSheet native (flutter_stripe) — clé éphémère Stripe pour le customer courant
    @PostMapping("/me/ephemeral-key")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<EphemeralKeyResponse> createEphemeralKey(
            @Valid @RequestBody EphemeralKeyRequest request) {
        return ResponseEntity.ok(
                paymentService.createEphemeralKey(requireFirebaseUid(), request.stripeVersion()));
    }

    // YadonyPaymentSheet — toggle « enregistrer la carte » sur un PaymentIntent non confirmé
    @PatchMapping("/intents/{paymentIntentId}/save-payment-method")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<Void> updateSavePaymentMethod(
            @PathVariable String paymentIntentId,
            @Valid @RequestBody UpdateSavePaymentMethodRequest request) {
        paymentService.updateSavePaymentMethod(paymentIntentId, request.save(), requireFirebaseUid());
        return ResponseEntity.noContent().build();
    }

    // Webhook Stripe — endpoint public (signature validée dans StripeWebhookIngestService)
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        ingestService.ingest(payload, sigHeader, StripeWebhookSource.PAYMENTS);
        return ResponseEntity.ok().build();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized", "problem.auth.token-required");
        }
        return (String) auth.getPrincipal();
    }
}
