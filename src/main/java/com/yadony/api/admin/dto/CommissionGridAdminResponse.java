package com.yadony.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridHistoryEntry;
import com.yadony.api.common.commission.CommissionGridWarning;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.payments.currency.SupportedCurrency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grille de commission vue par le back-office. Les champs de traçabilité ({@code note},
 * {@code createdBy}, {@code createdAt}, {@code cancelledAt}) ne sont renseignés que dans
 * l'historique ; {@code warnings} que dans la réponse à une programmation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommissionGridAdminResponse(UUID id, LocalDateTime effectiveFrom, List<CommissionTier> tiers,
                                          Map<SupportedCurrency, BigDecimal> minimums, String note,
                                          UUID createdBy, LocalDateTime createdAt, LocalDateTime cancelledAt,
                                          List<CommissionGridWarning> warnings) {

    public static CommissionGridAdminResponse from(CommissionGrid grid) {
        return new CommissionGridAdminResponse(grid.id(), grid.effectiveFrom(), grid.tiers(),
                ordered(grid.minimums()), null, null, null, null, null);
    }

    public static CommissionGridAdminResponse from(CommissionGridHistoryEntry entry) {
        CommissionGrid grid = entry.grid();
        return new CommissionGridAdminResponse(grid.id(), grid.effectiveFrom(), grid.tiers(),
                ordered(grid.minimums()), entry.note(), entry.createdBy(), entry.createdAt(), entry.cancelledAt(), null);
    }

    public CommissionGridAdminResponse withWarnings(List<CommissionGridWarning> warnings) {
        return new CommissionGridAdminResponse(id, effectiveFrom, tiers, minimums, note, createdBy, createdAt,
                cancelledAt, warnings);
    }

    /** Ordre des devises de l'enum : la réponse est stable d'un appel à l'autre. */
    private static Map<SupportedCurrency, BigDecimal> ordered(Map<SupportedCurrency, BigDecimal> minimums) {
        return minimums.isEmpty() ? Map.of() : new EnumMap<>(minimums);
    }
}
