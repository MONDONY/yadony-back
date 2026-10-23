package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne de l'onglet « Paiements » : {@code role} vaut {@code PAYER} ou {@code BENEFICIARY}.
 * {@code amountCents} et {@code commissionCents} sont des centièmes (montant x 100) pour toutes les devises,
 * XOF compris, comme les autres DTO admin.
 */
public record AdminUserPaymentRow(
        UUID id,
        UUID bidId,
        String status,
        String method,
        long amountCents,
        long commissionCents,
        String currency,
        Instant createdAt,
        Instant payoutHeldAt,
        boolean beneficiaryHeld,
        String role
) {}
