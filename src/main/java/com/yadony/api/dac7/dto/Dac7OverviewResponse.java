package com.yadony.api.dac7.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /admin/dac7/overview} (contrat 2). Aucune donnée personnelle : des compteurs et des codes.
 * {@code warnings} (tour 1) : remboursements à réconcilier + anomalies, jamais bloquants, à voir avant d'exporter.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dac7OverviewResponse(int year, boolean enabled, boolean remindersEnabled, int declarableSellers,
                                   int withTaxInfo, int missingTaxInfo, int identityReview, int unknownResidence,
                                   int nonDeclarable, int refundsToReconcile, int pendingSplits, int anomalies,
                                   List<String> invalidPartnerJurisdictions, Totals totals, boolean eurComplete,
                                   boolean commissionComplete, boolean exportReady, List<String> blockers,
                                   int warnings) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Totals(int operations, BigDecimal netEur, BigDecimal commissionEur) {
    }
}
