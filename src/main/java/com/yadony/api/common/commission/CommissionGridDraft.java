package com.yadony.api.common.commission;

import com.yadony.api.payments.currency.SupportedCurrency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Grille saisie par le propriétaire, avant validation.
 *
 * @param tiers         tranches dans l'ordre saisi (la première borne vaut 0).
 * @param minimums      commission minimum par devise ; une valeur pour chaque devise supportée.
 * @param effectiveFrom date d'effet (UTC) ; {@code null} ou à moins de 5 minutes dans le passé =
 *                      effet immédiat.
 * @param note          commentaire libre facultatif, 500 caractères au plus.
 */
public record CommissionGridDraft(List<CommissionTier> tiers, Map<SupportedCurrency, BigDecimal> minimums,
                                  LocalDateTime effectiveFrom, String note) {
}
