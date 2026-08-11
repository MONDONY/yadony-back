package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.StripeWebhookHandler;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class KycStripeWebhookHandler implements StripeWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(KycStripeWebhookHandler.class);

    private static final Set<String> SUPPORTED = Set.of(
            "identity.verification_session.verified",
            "identity.verification_session.requires_input",
            "identity.verification_session.canceled"
    );

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public KycStripeWebhookHandler(KycRepository kycRepository,
                                    UserRepository userRepository,
                                    AuditService auditService,
                                    ApplicationEventPublisher eventPublisher,
                                    ObjectMapper objectMapper) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void handle(Event event) {
        String eventType = event.getType();
        String rawJson = event.getDataObjectDeserializer().getRawJson();
        String sessionId;
        String lastErrorReason = null;
        String lastErrorCode = null;

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode idNode = root.path("id");
            if (idNode.isMissingNode() || idNode.isNull()) {
                log.warn("KYC webhook missing session id for event {}", eventType);
                return;
            }
            sessionId = idNode.asText();
            JsonNode lastError = root.path("last_error");
            if (!lastError.isMissingNode() && !lastError.isNull()) {
                lastErrorReason = lastError.path("reason").asText(null);
                lastErrorCode = lastError.path("code").asText(null);
            }
        } catch (Exception e) {
            log.warn("Could not parse KYC webhook payload for {}: {}", eventType, e.getMessage());
            return;
        }

        KycVerificationEntity kyc = kycRepository.findByStripeVerificationSessionId(sessionId)
                .orElse(null);
        if (kyc == null) { log.warn("No KYC record for session {}", sessionId); return; }

        UserEntity user = userRepository.findById(kyc.getUserId()).orElse(null);
        if (user == null) { log.warn("No user for KYC {}", kyc.getId()); return; }

        switch (eventType) {
            case "identity.verification_session.verified" -> {
                if (kyc.getStatus() != KycVerificationStatus.VERIFIED) {
                    kyc.setStatus(KycVerificationStatus.VERIFIED);
                    user.setKycStatus(KycStatus.VERIFIED);
                    kycRepository.save(kyc);
                    userRepository.save(user);
                    auditService.log("kyc_verification", kyc.getId(), "KYC_VERIFIED",
                            user.getId(), Map.of("sessionId", sessionId));
                    eventPublisher.publishEvent(
                            new UserKycVerifiedEvent(user.getId()));
                }
            }
            case "identity.verification_session.canceled" -> {
                if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
                    log.warn("Ignoring canceled event for already-VERIFIED session {}", sessionId);
                    return;
                }
                kyc.setStatus(KycVerificationStatus.REJECTED);
                kyc.setRejectionReason("session_canceled");
                kyc.setRejectionCode("session_canceled");
                user.setKycStatus(KycStatus.NOT_STARTED);
                kycRepository.save(kyc);
                userRepository.save(user);
                auditService.log("kyc_verification", kyc.getId(), "KYC_CANCELED",
                        user.getId(), Map.of("sessionId", sessionId, "reason", "session_canceled"));
            }
            case "identity.verification_session.requires_input" -> {
                if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
                    log.warn("Ignoring requires_input event for already-VERIFIED session {}", sessionId);
                    return;
                }
                kyc.setStatus(KycVerificationStatus.REJECTED);
                kyc.setRejectionReason(lastErrorReason != null ? lastErrorReason : "verification_failed");
                kyc.setRejectionCode(lastErrorCode != null ? lastErrorCode : "verification_failed");
                user.setKycStatus(KycStatus.REJECTED);
                kycRepository.save(kyc);
                userRepository.save(user);
                auditService.log("kyc_verification", kyc.getId(), "KYC_REJECTED",
                        user.getId(), Map.of("sessionId", sessionId, "reason", kyc.getRejectionReason()));
            }
        }
    }
}
