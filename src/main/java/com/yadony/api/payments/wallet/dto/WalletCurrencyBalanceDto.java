package com.yadony.api.payments.wallet.dto;

import java.math.BigDecimal;

public record WalletCurrencyBalanceDto(String currency, BigDecimal balance, boolean active) {
}
