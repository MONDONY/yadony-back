package com.yadony.api.payments.overview.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /payments/me/overview} — aperçu « Mon argent » (FLUTTER-HV).
 *
 * @param recentWindowDays fenêtre (jours) des montants « libérés / remboursés récemment »
 */
public record MoneyOverviewResponse(
        OffsetDateTime generatedAt,
        int recentWindowDays,
        List<WalletBalanceLineDto> wallet,
        RoleSection<TravelerTotalDto> traveler,
        RoleSection<SenderTotalDto> sender) {

    /** Totaux par devise et montants d'un côté (voyageur ou expéditeur). */
    public record RoleSection<T>(List<T> totals, List<MoneyItemDto> items) {
    }
}
