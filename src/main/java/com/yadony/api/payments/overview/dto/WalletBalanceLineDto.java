package com.yadony.api.payments.overview.dto;

import java.math.BigDecimal;

/** Solde disponible du portefeuille dans une devise. */
public record WalletBalanceLineDto(String currency, BigDecimal balance) {
}
