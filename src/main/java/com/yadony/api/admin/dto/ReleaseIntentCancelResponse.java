package com.yadony.api.admin.dto;

import java.util.UUID;

/**
 * Résultat de l'annulation d'une réservation de versement.
 *
 * @param result        {@code CANCELLED} (aucun Transfer chez Stripe : paiement de nouveau ESCROW,
 *                      remboursable) ou {@code REALIGNED} (un Transfer existait : versement confirmé)
 * @param transferId    Transfer Stripe trouvé ({@code REALIGNED}), sinon {@code null}
 * @param paymentStatus statut du paiement après l'opération
 */
public record ReleaseIntentCancelResponse(UUID paymentId, String result, String transferId, String paymentStatus) {}
