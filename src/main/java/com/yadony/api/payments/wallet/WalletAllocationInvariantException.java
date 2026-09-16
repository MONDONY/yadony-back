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

    /** Confort pour les appelants qui n'ont pas le détail du non-attribué (ex. tests) :
     *  l'écart entier computed/balance est imputé au non-attribué. */
    public WalletAllocationInvariantException(BigDecimal computed, BigDecimal balance) {
        this(computed, balance, balance.subtract(computed));
    }

    public BigDecimal getComputed() { return computed; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getUnallocated() { return unallocated; }
}
