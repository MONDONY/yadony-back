package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.billing.dto.CheckoutSessionResponse;
import com.yadony.api.billing.dto.PortalSessionResponse;
import com.yadony.api.billing.dto.ProSubscriptionResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeWebhookIngestService;
import com.yadony.api.common.stripe.StripeWebhookSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/billing")
public class BillingController {

    private final StripeBillingService stripeBillingService;
    private final ProSubscriptionRepository repository;
    private final UserRepository userRepository;
    private final StripeWebhookIngestService ingestService;

    public BillingController(StripeBillingService stripeBillingService,
                             ProSubscriptionRepository repository,
                             UserRepository userRepository,
                             StripeWebhookIngestService ingestService) {
        this.stripeBillingService = stripeBillingService;
        this.repository = repository;
        this.userRepository = userRepository;
        this.ingestService = ingestService;
    }

    @PostMapping("/checkout-session")
    public ResponseEntity<CheckoutSessionResponse> createCheckoutSession(
            Authentication authentication,
            @RequestParam(name = "cycle", defaultValue = "MONTHLY") BillingCycle cycle) {
        UUID userId = currentUserId(authentication);
        String url = stripeBillingService.createCheckoutSession(userId, cycle);
        return ResponseEntity.ok(new CheckoutSessionResponse(url));
    }

    @PostMapping("/portal-session")
    public ResponseEntity<PortalSessionResponse> createPortalSession(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        String url = stripeBillingService.createPortalSession(userId);
        return ResponseEntity.ok(new PortalSessionResponse(url));
    }

    @GetMapping("/subscription")
    public ResponseEntity<ProSubscriptionResponse> getSubscription(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        return repository.findByUserId(userId)
                .map(sub -> ResponseEntity.ok(new ProSubscriptionResponse(
                        sub.getStatus().grantsProAccess(),
                        sub.getStatus().name(),
                        sub.getSource().name(),
                        sub.getBillingCycle() == null ? null : sub.getBillingCycle().name(),
                        sub.getCurrentPeriodEnd(),
                        sub.isCancelAtPeriodEnd(),
                        sub.getGraceExpiresAt())))
                .orElseGet(() -> ResponseEntity.ok(new ProSubscriptionResponse(
                        false, "NONE", null, null, null, false, null)));
    }

    /** Endpoint public — la signature est vérifiée par StripeWebhookIngestService. */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        ingestService.ingest(payload, sigHeader, StripeWebhookSource.BILLING);
        return ResponseEntity.ok().build();
    }

    /**
     * Résout l'utilisateur courant depuis le principal Spring Security, qui est
     * le firebaseUid. Aucun identifiant utilisateur n'est jamais accepté du
     * client : ce serait laisser souscrire ou résilier pour autrui.
     */
    private UUID currentUserId(Authentication authentication) {
        String firebaseUid = authentication.getName();
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                        "unknown-user", "Unknown User", "Utilisateur introuvable"));
    }
}
