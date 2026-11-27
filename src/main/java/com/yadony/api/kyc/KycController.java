package com.yadony.api.kyc;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeWebhookIngestService;
import com.yadony.api.common.stripe.StripeWebhookSource;
import com.yadony.api.kyc.dto.KycAbandonReasonRequest;
import com.yadony.api.kyc.dto.KycSessionResponse;
import com.yadony.api.kyc.dto.KycStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/kyc")
public class KycController {

    private final KycService kycService;
    private final StripeWebhookIngestService ingestService;

    public KycController(KycService kycService, StripeWebhookIngestService ingestService) {
        this.kycService = kycService;
        this.ingestService = ingestService;
    }

    @PostMapping("/session")
    public ResponseEntity<KycSessionResponse> createSession() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(kycService.createSession(firebaseUid));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> abandonSession() {
        String firebaseUid = requireFirebaseUid();
        kycService.abandonSession(firebaseUid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/abandon-reason")
    public ResponseEntity<Void> abandonReason(@jakarta.validation.Valid @RequestBody KycAbandonReasonRequest body) {
        kycService.recordAbandonReason(requireFirebaseUid(), body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public ResponseEntity<KycStatusResponse> getStatus() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(kycService.getStatus(firebaseUid));
    }

    // Public endpoint — signature validated by StripeWebhookIngestService
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        ingestService.ingest(payload, sigHeader, StripeWebhookSource.KYC);
        return ResponseEntity.ok().build();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Token Firebase requis");
        }
        return (String) auth.getPrincipal();
    }
}
