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

import java.util.Locale;

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

    private static final int MAX_REJECTION_REASON_LENGTH = 512;
    private static final String FALLBACK_REJECTION = "verification_failed";

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

    /**
     * Correspondance des statuts Didit. Les statuts intermediaires n'ont aucun effet : la
     * ligne est deja PENDING, et la reecrire a chaque etape du parcours ne servirait qu'a
     * remplir audit_log.
     *
     * <p>Ecart assume avec le chemin Stripe historique : un abandon ou une expiration
     * n'alertent pas l'administration. Fermer la webview est le geste le plus courant du
     * parcours, alerter a chaque fois noierait les vraies alertes. Seul un refus alerte.
     */
    private void apply(String status, JsonNode payload, KycVerificationEntity kyc,
                       UserEntity user, String sessionId) {
        if (status == null) return;
        switch (status.toLowerCase(Locale.ROOT)) {
            case "approved" -> transitions.markVerified(kyc, user, sessionId);
            case "declined" -> transitions.markRejected(kyc, user, sessionId,
                    rejectionCode(payload), rejectionReason(payload));
            case "in review" -> transitions.markInReview(kyc, user, sessionId);
            case "abandoned" -> transitions.markRestartable(kyc, user, sessionId, "KYC_ABANDONED");
            case "expired", "kyc expired" ->
                    transitions.markRestartable(kyc, user, sessionId, "KYC_EXPIRED");
            default -> {
                // Not Started, In Progress, Awaiting User, Resubmitted : parcours en cours.
            }
        }
    }

    private String rejectionCode(JsonNode payload) {
        String risk = text(firstWarning(payload), "risk");
        return risk != null ? risk : FALLBACK_REJECTION;
    }

    private String rejectionReason(JsonNode payload) {
        String description = text(firstWarning(payload), "short_description");
        if (description == null) return FALLBACK_REJECTION;
        return description.length() > MAX_REJECTION_REASON_LENGTH
                ? description.substring(0, MAX_REJECTION_REASON_LENGTH)
                : description;
    }

    private JsonNode firstWarning(JsonNode payload) {
        JsonNode warnings = payload.path("decision").path("warnings");
        return warnings.isArray() && !warnings.isEmpty() ? warnings.get(0) : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
