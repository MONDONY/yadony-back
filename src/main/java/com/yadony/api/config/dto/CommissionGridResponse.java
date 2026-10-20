package com.yadony.api.config.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Grille de commission publique (spec 2026-10-18 §5.4). {@code current} est la grille en vigueur,
 * {@code null} tant qu'aucune n'est programmée (l'app retombe alors sur le taux unique de
 * {@code /config/commission-rate}) ; {@code next} est la grille programmée, {@code null} s'il n'y en a pas.
 *
 * <p>Les deux clés sont toujours présentes, même à {@code null} : {@code default-property-inclusion:
 * NON_NULL} les omettrait, et un client qui lit {@code json['current']} doit pouvoir distinguer « pas de
 * grille » d'une réponse mal formée.
 *
 * <p>{@code legacyRate} est le taux que le back applique à un objet SANS grille (bid, fil ou demande d'avant les
 * paliers, {@code commission_grid_id} NULL) : {@code commission_rate} de {@code platform_settings}, la même source
 * que {@code CommissionRateResolver.globalRate()}. Distinct de la 1re tranche que sert
 * {@code /config/commission-rate} sous grille. Toujours rempli, grille ou non.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CommissionGridResponse(GridView current, GridView next, BigDecimal legacyRate) {

    public static CommissionGridResponse of(Optional<CommissionGrid> current, Optional<CommissionGrid> next,
                                            BigDecimal legacyRate) {
        return new CommissionGridResponse(current.map(GridView::from).orElse(null),
                next.map(GridView::from).orElse(null), legacyRate);
    }

    /**
     * @param minimums minimum de commission par devise, clé = code en majuscules ({@code EUR}, {@code XOF}…)
     *                 dans l'unité de la devise.
     */
    public record GridView(UUID gridId, LocalDateTime effectiveFrom, List<CommissionTier> tiers,
                           Map<String, BigDecimal> minimums) {

        public static GridView from(CommissionGrid grid) {
            Map<String, BigDecimal> byCode = new LinkedHashMap<>();
            grid.minimums().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> byCode.put(e.getKey().name(), e.getValue()));
            return new GridView(grid.id(), grid.effectiveFrom(), grid.tiers(), byCode);
        }
    }
}
