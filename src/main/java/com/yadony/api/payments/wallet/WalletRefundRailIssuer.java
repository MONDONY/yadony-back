package com.yadony.api.payments.wallet;

import java.util.List;

/**
 * Émet vers un rail non-Stripe (pawaPay) les items PENDING d'une demande de remboursement
 * automatique dont {@code channel == AUTOMATIC_PAWAPAY}. Symétrique du bloc Stripe existant
 * dans {@link WalletSelfRefundService#issuePendingItems}, qui reste inchangé pour
 * {@code AUTOMATIC_STRIPE}.
 */
public interface WalletRefundRailIssuer {

    void issue(WalletRefundRequestEntity request, List<WalletRefundRequestItemEntity> items);

    /**
     * Réconciliation d'un item PROCESSING de ce rail à partir de l'état local de son opération
     * ({@code WalletSelfRefundService#reconcile}), sans appel réseau. L'appelant tient le verrou
     * de la demande {@code request}, fraîchement relue, et la résout ensuite.
     */
    void reconcile(WalletRefundRequestEntity request, WalletRefundRequestItemEntity item);
}
