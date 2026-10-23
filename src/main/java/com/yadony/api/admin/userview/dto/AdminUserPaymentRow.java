package com.yadony.api.admin.userview.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une ligne de l'onglet « Paiements » : {@code role} vaut {@code PAYER} ou {@code BENEFICIARY}.
 * Les montants sont dans la plus petite unité de la devise (EUR 2 décimales, XOF 0).
 */
public record AdminUserPaymentRow(
        UUID id,
        UUID bidId,
        String status,
        String method,
        long amountCents,
        long commissionCents,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime payoutHeldAt,
        boolean beneficiaryHeld,
        String role
) {}
