package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.dto.MobileMoneyCommissionMonthRow;
import com.yadony.api.payments.dto.MobileMoneyCommissionRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Commissions yadony du rail mobile money, en lecture seule.
 *
 * <p>Trois totaux par devise, parce que trois réalités comptables différentes :
 * <ul>
 *   <li><b>acquise</b> ({@code RELEASED}) — le voyageur a été versé, la commission est restée
 *       sur le solde pawaPay de yadony ;</li>
 *   <li><b>en séquestre</b> ({@code ESCROW}) — l'expéditeur a payé, la livraison n'est pas
 *       confirmée : yadony détient la totalité, dont une commission encore conditionnelle ;</li>
 *   <li><b>remboursée</b> ({@code REFUNDED}) — rendue avec le remboursement.</li>
 * </ul>
 *
 * <p>Les montants sont en centièmes de l'unité principale ({@link AdminWalletResponse#toCents}),
 * jamais en unité mineure de la devise. Le net est toujours {@code brut − commission} : c'est ce
 * que pawaPay a versé au voyageur, hors frais pawaPay, que yadony absorbe sur sa commission et
 * qui n'apparaissent pas ici (ils sont dans le relevé pawaPay).
 */
public record AdminMobileMoneyCommissionsResponse(
        LocalDateTime from,
        LocalDateTime to,
        List<CurrencyTotals> byCurrency,
        List<MonthlyTotals> monthly) {

    /**
     * @param earnedCommissionCents commission acquise (paiements {@code RELEASED})
     * @param escrowedCommissionCents commission encore conditionnelle (paiements {@code ESCROW})
     * @param refundedCommissionCents commission rendue (paiements {@code REFUNDED})
     */
    public record CurrencyTotals(
            String currency,
            long earnedCount,
            long earnedGrossCents,
            long earnedCommissionCents,
            long earnedNetCents,
            long escrowedCount,
            long escrowedGrossCents,
            long escrowedCommissionCents,
            long refundedCount,
            long refundedCommissionCents) {
    }

    /** @param month mois de création des paiements, au format {@code YYYY-MM} */
    public record MonthlyTotals(
            String month,
            String currency,
            long count,
            long grossCents,
            long commissionCents,
            long netCents) {
    }

    public static AdminMobileMoneyCommissionsResponse of(LocalDateTime from, LocalDateTime to,
                                                         List<MobileMoneyCommissionRow> rows,
                                                         List<MobileMoneyCommissionMonthRow> months) {
        // Une devise porte jusqu'à trois lignes (une par statut retenu) : on les replie en une
        // seule entrée. L'ordre des devises suit celui de la requête, déjà trié.
        List<String> currencies = rows.stream().map(MobileMoneyCommissionRow::currency).distinct().toList();
        List<CurrencyTotals> totals = currencies.stream().map(currency -> {
            MobileMoneyCommissionRow released = row(rows, currency, PaymentStatus.RELEASED);
            MobileMoneyCommissionRow escrow = row(rows, currency, PaymentStatus.ESCROW);
            MobileMoneyCommissionRow refunded = row(rows, currency, PaymentStatus.REFUNDED);
            return new CurrencyTotals(
                    currency,
                    count(released), cents(gross(released)), cents(commission(released)),
                    cents(gross(released).subtract(commission(released))),
                    count(escrow), cents(gross(escrow)), cents(commission(escrow)),
                    count(refunded), cents(commission(refunded)));
        }).toList();

        List<MonthlyTotals> monthly = months.stream()
                .map(m -> new MonthlyTotals(
                        "%04d-%02d".formatted(m.year(), m.month()),
                        m.currency(),
                        m.count(),
                        cents(nz(m.gross())),
                        cents(nz(m.commission())),
                        cents(nz(m.gross()).subtract(nz(m.commission())))))
                .toList();

        return new AdminMobileMoneyCommissionsResponse(from, to, totals, monthly);
    }

    private static MobileMoneyCommissionRow row(List<MobileMoneyCommissionRow> rows, String currency,
                                                PaymentStatus status) {
        return rows.stream()
                .filter(r -> currency.equals(r.currency()) && r.status() == status)
                .findFirst()
                .orElse(null);
    }

    private static long count(MobileMoneyCommissionRow row) {
        return row == null ? 0L : row.count();
    }

    private static BigDecimal gross(MobileMoneyCommissionRow row) {
        return row == null ? BigDecimal.ZERO : nz(row.gross());
    }

    private static BigDecimal commission(MobileMoneyCommissionRow row) {
        return row == null ? BigDecimal.ZERO : nz(row.commission());
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long cents(BigDecimal amount) {
        return AdminWalletResponse.toCents(amount);
    }
}
