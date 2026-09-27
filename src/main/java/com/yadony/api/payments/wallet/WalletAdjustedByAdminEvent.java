package com.yadony.api.payments.wallet;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Publié après une correction de solde admin, écouté par {@code notifications/} pour
 * prévenir l'utilisateur. Ne porte PAS le motif : il est interne à l'équipe.
 *
 * @param amount montant absolu de la correction, dans {@code currency}
 */
public record WalletAdjustedByAdminEvent(UUID userId,
                                         String currency,
                                         WalletAdjustmentDirection direction,
                                         BigDecimal amount,
                                         UUID transactionId) {
}
