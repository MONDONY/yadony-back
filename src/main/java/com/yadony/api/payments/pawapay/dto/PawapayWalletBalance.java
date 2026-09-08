package com.yadony.api.payments.pawapay.dto;

import java.math.BigDecimal;

/** Une ligne de {@code GET /v2/wallet-balances} : solde du wallet pawaPay pour un pays/devise. */
public record PawapayWalletBalance(String countryAlpha3, String currency, BigDecimal balance) {}
