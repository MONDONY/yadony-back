package com.yadony.api.payments.events;

import java.util.UUID;

/**
 * Le deposit mobile money a échoué (PIN refusé, solde insuffisant, opérateur
 * indisponible…) : le paiement reste PENDING, l'expéditeur peut relancer un nouveau
 * deposit depuis l'app.
 *
 * <p>{@code failureCode} vient de pawaPay (callback ou poller) — déjà borné à 64
 * caractères par {@code MobileMoneyBidPaymentService#notifyDepositFailed} avant
 * publication, comme toute valeur non authentifiée de ce rail.
 */
public record MobileMoneyDepositFailedEvent(UUID bidId, UUID senderId, String failureCode) {}
