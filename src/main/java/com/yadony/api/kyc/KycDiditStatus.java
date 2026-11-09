package com.yadony.api.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.auth.UserEntity;

import java.util.Locale;

/**
 * Correspondance des statuts Didit vers {@link KycStatusTransitionService}, partagee par le
 * webhook et par le rattrapage ({@link KycPendingReconciler}) : les deux lisent le meme statut,
 * chez le meme fournisseur, et doivent produire exactement les memes effets.
 *
 * <p>Les statuts intermediaires n'ont aucun effet : la ligne est deja PENDING, et la reecrire a
 * chaque etape du parcours ne servirait qu'a remplir audit_log.
 *
 * <p>Ecart assume avec le chemin Stripe historique : un abandon ou une expiration n'alertent
 * pas l'administration. Fermer la webview est le geste le plus courant du parcours, alerter a
 * chaque fois noierait les vraies alertes. Seul un refus alerte.
 */
final class KycDiditStatus {

    private static final int MAX_REJECTION_REASON_LENGTH = 512;
    private static final String FALLBACK_REJECTION = "verification_failed";

    private KycDiditStatus() {
    }

    /**
     * @param payload charge utile du webhook ({@code decision.warnings}) ou decision relue par
     *                l'API ({@code warnings} a la racine) : les deux emplacements sont lus.
     */
    static void apply(KycStatusTransitionService transitions, String status, JsonNode payload,
                      KycVerificationEntity kyc, UserEntity user, String sessionId) {
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

    /** Vrai pour un statut qui met fin au parcours (decision ou abandon), faux s'il est en cours. */
    static boolean isFinal(String status) {
        if (status == null) return false;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "approved", "declined", "abandoned", "expired", "kyc expired" -> true;
            default -> false;
        };
    }

    private static String rejectionCode(JsonNode payload) {
        String risk = text(firstWarning(payload), "risk");
        return risk != null ? risk : FALLBACK_REJECTION;
    }

    private static String rejectionReason(JsonNode payload) {
        String description = text(firstWarning(payload), "short_description");
        if (description == null) return FALLBACK_REJECTION;
        return description.length() > MAX_REJECTION_REASON_LENGTH
                ? description.substring(0, MAX_REJECTION_REASON_LENGTH)
                : description;
    }

    private static JsonNode firstWarning(JsonNode payload) {
        JsonNode warnings = payload.path("decision").path("warnings");
        if (!warnings.isArray() || warnings.isEmpty()) {
            warnings = payload.path("warnings");
        }
        return warnings.isArray() && !warnings.isEmpty() ? warnings.get(0) : null;
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
