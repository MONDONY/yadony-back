package com.yadony.api.payments.wallet;

import java.math.BigDecimal;

/** Le rejeu du ledger ne retombe pas sur le solde du wallet : on ne rembourse rien automatiquement. */
public class WalletAllocationInvariantException extends RuntimeException {

    public WalletAllocationInvariantException(BigDecimal computed, BigDecimal balance) {
        super("Allocation " + computed.toPlainString() + " differente du solde " + balance.toPlainString());
    }
}
