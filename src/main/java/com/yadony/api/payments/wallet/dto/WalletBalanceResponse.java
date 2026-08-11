package com.yadony.api.payments.wallet.dto;

import java.math.BigDecimal;
import java.util.List;

public class WalletBalanceResponse {

    private BigDecimal balance;
    private String currency;
    private List<WalletTransactionDto> transactions;
    private List<WalletCurrencyBalanceDto> balances;

    public WalletBalanceResponse(BigDecimal balance, String currency, List<WalletTransactionDto> transactions,
                                 List<WalletCurrencyBalanceDto> balances) {
        this.balance = balance;
        this.currency = currency;
        this.transactions = transactions;
        this.balances = balances;
    }

    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public List<WalletTransactionDto> getTransactions() { return transactions; }
    public List<WalletCurrencyBalanceDto> getBalances() { return balances; }
}
