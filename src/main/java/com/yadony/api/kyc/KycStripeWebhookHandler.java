package com.yadony.api.kyc;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.stripe.StripeWebhookHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Traduit les evenements Stripe Identity vers {@link KycStatusTransitionService}.
 *
 * <p>Ne porte plus aucune regle metier : seulement le decodage d'une charge utile Stripe.
 * Le jour ou Stripe Identity est retire, cette classe se supprime sans que rien d'autre ne
 * bouge.
 */
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
    private final KycStatusTransitionService transitions;
    private final ObjectMapper objectMapper;

    public KycStripeWebhookHandler(KycRepository kycRepository,
                                   UserRepository userRepository,
                                   KycStatusTransitionService transitions,
                                   ObjectMapper objectMapper) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.transitions = transitions;
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

        KycVerificationEntity kyc = kycRepository.findByVerificationSessionId(sessionId).orElse(null);
        if (kyc == null) { log.warn("No KYC record for session {}", sessionId); return; }

        UserEntity user = userRepository.findById(kyc.getUserId()).orElse(null);
        if (user == null) { log.warn("No user for KYC {}", kyc.getId()); return; }

        switch (eventType) {
            case "identity.verification_session.verified" ->
                    transitions.markVerified(kyc, user, sessionId);
            case "identity.verification_session.canceled" ->
                    transitions.markRestartable(kyc, user, sessionId, "KYC_CANCELED");
            case "identity.verification_session.requires_input" ->
                    transitions.markRejected(kyc, user, sessionId,
                            lastErrorCode != null ? lastErrorCode : "verification_failed",
                            lastErrorReason != null ? lastErrorReason : "verification_failed");
            default -> log.warn("Unsupported KYC webhook event {}", eventType);
        }
    }
}
