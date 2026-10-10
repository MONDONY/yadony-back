package com.yadony.api.payments.overview.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /payments/me/overview} — aperçu « Mon argent » (FLUTTER-HV).
 *
 * @param recentWindowDays fenêtre (jours) des montants « libérés / remboursés récemment »
 * @param wallet           soldes par devise, devise active en tête (FLUTTER-J4)
 * @param activeCurrency   devise active de l'utilisateur, code ISO en majuscules ; ajoutée en fin
 *                         d'objet (FLUTTER-J4), les apps antérieures l'ignorent
 */
public record MoneyOverviewResponse(
        OffsetDateTime generatedAt,
        int recentWindowDays,
        List<WalletBalanceLineDto> wallet,
        RoleSection<TravelerTotalDto> traveler,
        RoleSection<SenderTotalDto> sender,
        String activeCurrency) {

    /** Totaux par devise et montants d'un côté (voyageur ou expéditeur). */
    public record RoleSection<T>(List<T> totals, List<MoneyItemDto> items) {
    }
}
