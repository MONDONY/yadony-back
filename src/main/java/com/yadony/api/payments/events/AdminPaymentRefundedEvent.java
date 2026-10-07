package com.yadony.api.payments.events;

import java.util.UUID;

/**
 * Un administrateur a remboursé un paiement à la main ({@code POST /admin/payments/{id}/refund},
 * ou relance d'un remboursement mobile money mort). Publié une fois le remboursement
 * effectivement lancé (annulation/refund Stripe réussi, refund pawaPay soumis et accepté) ;
 * les écouteurs le reçoivent après commit.
 *
 * <p>Écouté par {@code promo/PromoReleaseListener} : la commission remisée revient à
 * l'expéditeur, son code promo lui est rendu.
 *
 * @param bidId               bid du paiement, nul pour un paiement porté par un fil de négociation
 * @param negotiationThreadId fil de négociation du paiement, nul pour un bid classique
 */
public record AdminPaymentRefundedEvent(UUID paymentId, UUID bidId, UUID negotiationThreadId, UUID adminId) {
}
