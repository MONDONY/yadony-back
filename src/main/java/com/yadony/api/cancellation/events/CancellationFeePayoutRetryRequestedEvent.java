package com.yadony.api.cancellation.events;

import java.util.UUID;

/**
 * Un administrateur relance le versement d'un dédommagement d'annulation en attente
 * ({@code POST /admin/cancellation-fees/{feeId}/retry-payout}) : la ligne est réarmée
 * ({@code payout_rearmed_at}) dans la transaction du geste, le paquet {@code payments} tente aussitôt le versement
 * après le commit, par le chemin habituel et ses gardes.
 */
public record CancellationFeePayoutRetryRequestedEvent(UUID feeId, UUID adminId) {
}
