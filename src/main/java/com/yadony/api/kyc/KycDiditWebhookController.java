package com.yadony.api.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.kyc.provider.didit.DiditProperties;
import com.yadony.api.kyc.provider.didit.DiditWebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook Didit. Endpoint public, authentifie par signature.
 *
 * <p>Traduit les statuts Didit vers {@link KycStatusTransitionService} et ne porte aucune
 * regle metier : c'est le pendant de {@code KycStripeWebhookHandler}, dans l'autre chemin.
 *
 * <p>Repond 200 des que la signature est valide, meme pour un evenement ignore : Didit
 * reessaie deux fois sur 5xx ou 404, et faire reessayer un evenement qu'on ne traite pas ne
 * sert a rien. Le traitement doit tenir sous les 5 secondes de son delai d'attente.
 */
@RestController
public class KycDiditWebhookController {

    private static final Logger log = LoggerFactory.getLogger(KycDiditWebhookController.class);

    /** Seul type d'evenement utile : le changement de statut d'une session. */
    private static final String STATUS_UPDATED = "status.updated";

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final KycStatusTransitionService transitions;
    private final DiditWebhookSignatureVerifier signatureVerifier;
    private final DiditProperties properties;
    private final ObjectMapper objectMapper;

    public KycDiditWebhookController(KycRepository kycRepository,
                                     UserRepository userRepository,
                                     KycStatusTransitionService transitions,
                                     DiditWebhookSignatureVerifier signatureVerifier,
                                     DiditProperties properties,
                                     ObjectMapper objectMapper) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.transitions = transitions;
        this.signatureVerifier = signatureVerifier;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Le corps est lu en {@code byte[]} : la signature porte sur les octets exacts transmis,
     * qu'un decodage en {@code String} pourrait alterer.
     */
    @PostMapping("/kyc/webhook/didit")
    public ResponseEntity<Void> handle(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp) {

        if (!signatureVerifier.verify(rawBody, signature, timestamp)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Charge utile Didit illisible ({})", e.getClass().getSimpleName());
            return ResponseEntity.ok().build();
        }

        String webhookType = text(payload, "webhook_type");
        if (!STATUS_UPDATED.equals(webhookType)) {
            return ResponseEntity.ok().build();
        }

        // Champ EXIGE, pas seulement verifie s'il est la : cette garde n'existe que parce
        // qu'un meme secret peut signer du bac a sable et de la production (sinon la
        // signature suffirait). L'accepter absent la rendrait contournable par omission.
        String environment = text(payload, "environment");
        if (environment == null || !environment.equalsIgnoreCase(properties.environment())) {
            log.warn("Webhook Didit d'un autre environnement ({}) — ignoré", environment);
            return ResponseEntity.ok().build();
        }

        String sessionId = text(payload, "session_id");
        if (sessionId == null) {
            log.warn("Webhook Didit sans session_id — ignoré");
            return ResponseEntity.ok().build();
        }

        KycVerificationEntity kyc = kycRepository.findByVerificationSessionId(sessionId).orElse(null);
        if (kyc == null) {
            log.warn("No KYC record for Didit session {}", sessionId);
            return ResponseEntity.ok().build();
        }

        UserEntity user = userRepository.findById(kyc.getUserId()).orElse(null);
        if (user == null) {
            log.warn("No user for KYC {}", kyc.getId());
            return ResponseEntity.ok().build();
        }

        apply(text(payload, "status"), payload, kyc, user, sessionId);
        return ResponseEntity.ok().build();
    }

    private void apply(String status, JsonNode payload, KycVerificationEntity kyc,
                       UserEntity user, String sessionId) {
        KycDiditStatus.apply(transitions, status, payload, kyc, user, sessionId);
    }

    private static String text(JsonNode node, String field) {
        return KycDiditStatus.text(node, field);
    }
}
