package com.yadony.api.cancellation.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ce que l'expéditeur perdrait s'il annulait son colis maintenant ({@code GET /cancellations/bids/{bidId}/fee-preview}).
 * Montants dans la devise du paiement, à l'échelle de la devise (2 décimales pour l'euro, aucune pour le franc CFA).
 *
 * @param rate         taux du barème appliqué (0.20 = 20 %), 0 quand aucun frais ne s'applique
 * @param fee          frais BRUTS retenus sur le remboursement (ce que l'expéditeur perd, pas ce que touche le
 *                     voyageur), 0 quand aucun frais ne s'applique
 * @param currency     devise du paiement (ISO 4217), à défaut celle du colis
 * @param refundAmount montant rendu à l'expéditeur (payé moins frais) ; 0 si rien n'a été payé en ligne (espèces)
 * @param nextTierAt   instant de la prochaine hausse du taux : le taux affiché vaut jusqu'à cet instant inclus ; nul au
 *                     palier le plus cher ou quand aucun frais ne peut s'appliquer
 */
public record CancellationFeePreviewResponse(BigDecimal rate, BigDecimal fee, String currency, BigDecimal refundAmount,
                                             Instant nextTierAt) {}
