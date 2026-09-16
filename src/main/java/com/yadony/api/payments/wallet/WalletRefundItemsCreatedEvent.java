package com.yadony.api.payments.wallet;

import java.util.UUID;

/**
 * Publiée par {@link WalletSelfRefundService#request} une fois la demande automatique et ses
 * items PENDING enregistrés. {@link WalletRefundIssueListener} n'émet les {@code Refund.create}
 * qu'au commit de cette transaction : un remboursement Stripe ne part jamais pour une demande
 * que la base pourrait encore annuler.
 */
public record WalletRefundItemsCreatedEvent(UUID refundRequestId) {}
