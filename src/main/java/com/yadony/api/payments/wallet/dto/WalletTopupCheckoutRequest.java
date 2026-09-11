package com.yadony.api.payments.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Montant à recharger, dans la devise active du portefeuille (résolue côté serveur). */
public record WalletTopupCheckoutRequest(
        @NotNull @DecimalMin(value = "1", message = "Le montant minimum est une unité de la devise")
        BigDecimal amount
) {}
