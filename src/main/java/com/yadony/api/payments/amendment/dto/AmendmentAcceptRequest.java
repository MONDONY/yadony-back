package com.yadony.api.payments.amendment.dto;

import com.yadony.api.payments.cash.CommissionSource;
import jakarta.validation.constraints.Size;

/**
 * Acceptation par le voyageur. Rail espèces seulement : d'où vient la commission de la modification
 * ({@code commissionSource}) et, pour un portefeuille dans une autre devise, laquelle ({@code fundingCurrency}).
 * Ignoré par les autres rails.
 */
public record AmendmentAcceptRequest(CommissionSource commissionSource, @Size(max = 3) String fundingCurrency) {
}
