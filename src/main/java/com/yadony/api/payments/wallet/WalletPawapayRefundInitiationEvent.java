package com.yadony.api.payments.wallet;

import java.util.UUID;

/**
 * Publiée par {@link WalletPawapayRefundIssuer} quand une opération pawaPay (REFUND ou PAYOUT) vient
 * d'être réservée et liée à l'item {@code itemId}, dans la transaction qui écrit ce lien.
 * {@link WalletRefundOutcomeListener#onInitiationRequested} n'appelle pawaPay qu'au commit de cette
 * transaction : un rollback ne peut plus effacer le lien d'une opération déjà partie, que la
 * reprise planifiée émettrait alors une seconde fois sous un autre identifiant.
 */
public record WalletPawapayRefundInitiationEvent(UUID itemId, UUID operationId) {}
