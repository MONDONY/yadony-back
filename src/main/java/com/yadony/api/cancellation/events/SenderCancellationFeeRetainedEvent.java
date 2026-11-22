package com.yadony.api.cancellation.events;

import com.yadony.api.cancellation.fee.CancellationFeeReason;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * La retenue d'annulation d'un expéditeur fautif vient d'être réglée ({@code DUE -> RETAINED}) : son remboursement est
 * parti, moins les frais. Publié une seule fois, au passage d'état, sous le verrou de la ligne et dans la transaction du
 * remboursement ({@code CancellationFeeRatePortAdapter#markRetained}) ; écouté après commit par {@code notifications}
 * pour prévenir l'expéditeur (y compris après une absence confirmée, où il n'a vu aucun aperçu).
 *
 * <p>À ne pas confondre avec {@code payments.fee.CancellationFeeRetainedEvent} : celui-là, interne aux paiements,
 * déclenche le versement au voyageur et ne porte que le colis.
 *
 * @param retainedAmount frais bruts retenus sur le remboursement
 * @param refundedAmount montant rendu à l'expéditeur
 * @param currency       devise du paiement
 */
public record SenderCancellationFeeRetainedEvent(UUID feeId, UUID bidId, UUID senderId, CancellationFeeReason reason,
                                                 BigDecimal retainedAmount, BigDecimal refundedAmount,
                                                 String currency) {}
