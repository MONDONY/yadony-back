package com.yadony.api.payments.wallet;

import java.math.BigDecimal;

/** Le rejeu du ledger ne retombe pas sur le solde du wallet : on ne rembourse rien automatiquement. */
public class WalletAllocationInvariantException extends RuntimeException {

    private final BigDecimal computed;
    private final BigDecimal balance;
    private final BigDecimal unallocated;

    public WalletAllocationInvariantException(BigDecimal computed, BigDecimal balance, BigDecimal unallocated) {
        super("Allocation " + computed.toPlainString() + " differente du solde " + balance.toPlainString()
                + " (non attribue " + unallocated.toPlainString() + ")");
        this.computed = computed;
        this.balance = balance;
        this.unallocated = unallocated;
    }

    public BigDecimal getComputed() { return computed; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getUnallocated() { return unallocated; }
}
