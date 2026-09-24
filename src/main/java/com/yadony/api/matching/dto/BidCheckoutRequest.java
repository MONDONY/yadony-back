package com.yadony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BidCheckoutRequest(
        @NotNull UUID announcementId,
        // Nullable en mode MIXED (grille seule) — validé côté service
        @DecimalMin(value = "0.0") BigDecimal weightKg,
        @Size(max = 1000) String description,
        // Multi-sélection jointe par virgule côté front : le catalogue canonique complet
        // joint fait 216 caractères, donc 500 laisse de la marge pour la saisie libre.
        // NE PAS redescendre sous ~220 : deux libellés canoniques joints dépassent déjà
        // 50 (l'ancienne limite) — cf. V171__unify_content_categories.sql.
        @Size(max = 500) String contentCategory,
        @Size(max = 200) String recipientName,
        @Size(max = 30) String recipientPhone,
        @AssertTrue(message = "{validation.bid.disclaimer.signed}") Boolean disclaimerSigned,
        @Size(max = 4) List<String> photoKeys,
        @Valid List<BidGridItemRequest> gridItems,
        // null/true → carte réutilisable (setup_future_usage=off_session) ; false → non enregistrée.
        // Modifiable ensuite via PATCH /payments/intents/{id}/save-payment-method.
        Boolean savePaymentMethod
) {
    /** Constructeur historique (sans savePaymentMethod) — équivalent au défaut null. */
    public BidCheckoutRequest(UUID announcementId, BigDecimal weightKg,
                              String description, String contentCategory, String recipientName,
                              String recipientPhone, Boolean disclaimerSigned, List<String> photoKeys,
                              List<BidGridItemRequest> gridItems) {
        this(announcementId, weightKg, description, contentCategory,
                recipientName, recipientPhone, disclaimerSigned, photoKeys, gridItems, null);
    }
}
