package com.yadony.api.cancellation.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Le dédommagement d'annulation vient d'être versé au voyageur ({@code RETAINED} ou {@code PAYOUT_PENDING} ->
 * {@code PAID_OUT}) : Transfer Stripe ou versement pawaPay confirmé, ou rien à verser (net nul). Publié une seule fois,
 * au passage d'état, sous le verrou de la ligne ({@code CancellationFeePayoutPortAdapter#markPaidOut}) : un rejeu
 * (webhook, issue pawaPay, planificateur) trouve la ligne déjà versée et ne publie rien. Écouté après commit par
 * {@code notifications}.
 *
 * @param travelerNet net versé au voyageur (0 : rien n'a été versé)
 * @param currency    devise du paiement
 */
public record CancellationFeePaidOutEvent(UUID feeId, UUID bidId, UUID travelerId, BigDecimal travelerNet,
                                          String currency) {}
