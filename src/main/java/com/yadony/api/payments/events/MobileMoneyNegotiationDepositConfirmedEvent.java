package com.yadony.api.payments.events;

import java.util.UUID;

/**
 * Dépôt pawaPay confirmé sur le paiement d'un fil de négociation : le séquestre est posé,
 * {@code requests/} doit sceller le fil (ou, s'il n'est plus scellable, demander le
 * remboursement). Publié dans la transaction qui a posé le claim ESCROW.
 */
public record MobileMoneyNegotiationDepositConfirmedEvent(UUID threadId, UUID paymentId, UUID operationId) {}
