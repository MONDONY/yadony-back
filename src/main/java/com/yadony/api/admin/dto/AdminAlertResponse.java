package com.yadony.api.admin.dto;

import com.yadony.api.admin.AdminAlertEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AdminAlertResponse(
        UUID id,
        String type,
        String severity,
        String detail,
        Map<String, Object> payload,
        boolean resolved,
        OffsetDateTime resolvedAt,
        LocalDateTime createdAt,
        /**
         * Paiement visé par l'alerte, pour les boutons du back-office (resynchroniser, libérer) :
         * {@code payload.paymentId} s'il existe, sinon l'identifiant en suffixe du type
         * ({@code RECON_STRIPE_<id>}, {@code ESCROW_CAPTURE_FAILED_<id>}…). {@code null} sinon.
         */
        UUID paymentId
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AdminAlertResponse from(AdminAlertEntity e) {
        Map<String, Object> payload = parsePayload(e.getPayload());
        return new AdminAlertResponse(
                e.getId(),
                e.getType(),
                e.getSeverity(),
                e.getDetail(),
                payload,
                e.isResolved(),
                e.getResolvedAt(),
                e.getCreatedAt(),
                paymentIdOf(e.getType(), payload)
        );
    }

    /** Préfixes d'alertes dont le suffixe est l'identifiant d'un paiement. */
    private static final java.util.List<String> PAYMENT_SUFFIXED = java.util.List.of(
            "RECON_STRIPE_", "ESCROW_CAPTURE_FAILED_", "DELIVERY_NOT_ESCROW_", "DELIVERY_PAYMENT_NOT_IN_ESCROW_",
            "PARTIAL_REFUND_HOLD_",
            "PAYOUT_HELD_", "PAYOUT_STRIPE_UNUSABLE_");

    static UUID paymentIdOf(String type, Map<String, Object> payload) {
        UUID fromPayload = uuid(payload.get("paymentId"));
        if (fromPayload != null) {
            return fromPayload;
        }
        if (type == null) {
            return null;
        }
        // Rapprochement des commissions carte : la référence est l'identifiant du colis, pas d'un paiement.
        Object ecart = payload.get("ecart");
        if (type.startsWith("RECON_") && ecart != null && ecart.toString().startsWith("COMMISSION_")) {
            return null;
        }
        for (String prefix : PAYMENT_SUFFIXED) {
            if (type.startsWith(prefix)) {
                return uuid(type.substring(prefix.length()));
            }
        }
        return null;
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** La colonne payload est du TEXT JSON ; le front attend un objet. */
    private static Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("raw", raw);
        }
    }
}
