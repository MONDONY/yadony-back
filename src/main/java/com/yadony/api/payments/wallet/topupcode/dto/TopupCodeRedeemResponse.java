package com.yadony.api.payments.wallet.topupcode.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Crédit obtenu : montant et devise du code, solde de cette devise après crédit. */
public record TopupCodeRedeemResponse(BigDecimal amount, String currency, BigDecimal balance, UUID transactionId) {}
