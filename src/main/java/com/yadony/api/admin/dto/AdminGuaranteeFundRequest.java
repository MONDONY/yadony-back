package com.yadony.api.admin.dto;

import java.util.UUID;

/**
 * @param amountCents montant en centièmes de l'unité principale de {@code currency}
 * @param beneficiaryUserId bénéficiaire du versement, obligatoire
 * @param currency devise du versement (code ISO) ; absente, celle du bid du litige
 */
public record AdminGuaranteeFundRequest(
        int amountCents,
        UUID beneficiaryUserId,
        String reason,
        String currency
) {
    /** Compatibilité : les appels antérieurs à la devise explicite. */
    public AdminGuaranteeFundRequest(int amountCents, UUID beneficiaryUserId, String reason) {
        this(amountCents, beneficiaryUserId, reason, null);
    }
}
