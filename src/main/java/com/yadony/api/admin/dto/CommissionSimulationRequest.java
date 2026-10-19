package com.yadony.api.admin.dto;

import com.yadony.api.payments.currency.SupportedCurrency;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Simulation de marge : une grille brouillon OU une grille existante ({@code gridId}), jamais les deux.
 *
 * @param nets           montants nets du voyageur à comparer, dans {@code currency}.
 * @param vatRatePercent TVA sur la commission, en pour cent ; 20 si absente.
 * @param effectiveFrom  date à laquelle se lit la grille « actuelle » de comparaison ; maintenant si absente.
 */
public record CommissionSimulationRequest(CommissionGridRequest draft, UUID gridId,
                                          @NotNull(message = "La devise est obligatoire") SupportedCurrency currency,
                                          @NotEmpty(message = "Au moins un montant net est requis")
                                          @Size(max = 50, message = "50 montants au plus")
                                          List<@NotNull @Positive(message = "Le net doit être positif") BigDecimal> nets,
                                          BigDecimal vatRatePercent, LocalDateTime effectiveFrom) {
}
