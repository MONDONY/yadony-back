package com.yadony.api.auth.dto;

/**
 * Story 9.8 — Éligibilité à la suppression de compte (lecture seule, sans effet de bord).
 *
 * <p>Un solde wallet positif ne bloque plus {@code canDelete} — Apple 5.1.1(v) exige que la
 * suppression de compte reste toujours possible en self-service, sans dépendre d'un ticket
 * résolu par un humain. {@code blockedReasonCode} vaut donc {@code null} sauf pour
 * {@code "active-transactions"} (escrow en cours, temporaire). {@code hasWalletBalance}
 * reste purement informatif : le front peut prévenir l'utilisateur qu'un ticket de
 * remboursement sera ouvert automatiquement (cf. {@code UserService#openWalletRefundTicketIfNeeded}).
 */
public record DeletionEligibilityResponse(
        boolean canDelete,
        String blockedReasonCode,
        boolean hasWalletBalance
) {}
