package com.yadony.api.payments.wallet.dto;

import java.math.BigDecimal;
import java.util.List;

public class WalletBalanceResponse {

    private BigDecimal balance;
    private String currency;
    private List<WalletTransactionDto> transactions;
    private List<WalletCurrencyBalanceDto> balances;
    private boolean refundEligible;
    /** Somme de tous les portefeuilles convertie dans {@code currency}, null si aucun taux. */
    private BigDecimal estimatedTotal;
    /** false dès qu'une devise détenue est exclue du total faute de taux. */
    private boolean estimateComplete;

    public WalletBalanceResponse(BigDecimal balance, String currency, List<WalletTransactionDto> transactions,
                                 List<WalletCurrencyBalanceDto> balances, boolean refundEligible,
                                 BigDecimal estimatedTotal, boolean estimateComplete) {
        this.balance = balance;
        this.currency = currency;
        this.transactions = transactions;
        this.balances = balances;
        this.refundEligible = refundEligible;
        this.estimatedTotal = estimatedTotal;
        this.estimateComplete = estimateComplete;
    }

    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public List<WalletTransactionDto> getTransactions() { return transactions; }
    public List<WalletCurrencyBalanceDto> getBalances() { return balances; }
    public boolean isRefundEligible() { return refundEligible; }
    public BigDecimal getEstimatedTotal() { return estimatedTotal; }
    public boolean isEstimateComplete() { return estimateComplete; }
}
