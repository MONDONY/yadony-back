package com.yadony.api.admin.metrics;

import com.yadony.api.admin.dto.AdminWalletResponse;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.dto.PaymentVolumeRow;
import java.math.BigDecimal;
import java.util.List;

public record AdminOverviewResponse(
        Users users,
        Announcements announcements,
        Bids bids,
        Gmv gmv,
        List<GmvByCurrency> gmvByCurrency,
        Queues queues
) {

    public record Users(
            long total,
            long active,
            long suspended,
            long banned,
            long pendingDeletion,
            long kycVerified,
            long kycPending,
            long pro,
            long newLast7d,
            long newLast30d
    ) {}

    public record Announcements(
            long active,
            long full,
            long inProgress,
            long completed,
            long cancelled
    ) {}

    public record Bids(
            long pending,
            long accepted,
            long inTransit,
            long completed,
            long cancelled,
            long total
    ) {}

    /**
     * Ancien contrat, en unités : conservé pour les back-offices antérieurs à la ventilation par
     * devise, qui l'affichent en euros. Il ne porte donc que la ligne EUR, jamais un mélange.
     * Ne pas l'étendre : {@link GmvByCurrency} fait foi.
     */
    public record Gmv(
            BigDecimal escrowHeld,
            BigDecimal released,
            BigDecimal refunded,
            BigDecimal commission
    ) {}

    /**
     * Volumes d'une devise, en centièmes de l'unité principale ({@link AdminWalletResponse#toCents}).
     * Un EUR et un XOF ne s'additionnent jamais : une entrée par devise.
     *
     * @param escrowHeldCents montants détenus en séquestre ({@code ESCROW})
     * @param releasedCents montants libérés aux voyageurs ({@code RELEASED})
     * @param refundedCents montants remboursés ({@code REFUNDED})
     * @param commissionCents commission acquise ({@code RELEASED})
     */
    public record GmvByCurrency(
            String currency,
            long escrowHeldCents,
            long releasedCents,
            long refundedCents,
            long commissionCents
    ) {}

    public record Queues(
            long openDisputes,
            long pendingNoShows,
            long unresolvedAlerts,
            long pendingKyc,
            long escrowJ48
    ) {}

    /** Replie les lignes (devise, statut) en une entrée par devise, dans l'ordre des lignes. */
    public static List<GmvByCurrency> foldByCurrency(List<PaymentVolumeRow> rows) {
        List<String> currencies = rows.stream().map(PaymentVolumeRow::currency).distinct().toList();
        return currencies.stream().map(currency -> new GmvByCurrency(
                currency,
                cents(amount(rows, currency, PaymentStatus.ESCROW)),
                cents(amount(rows, currency, PaymentStatus.RELEASED)),
                cents(refunded(rows, currency, PaymentStatus.REFUNDED)),
                cents(commission(rows, currency, PaymentStatus.RELEASED)))).toList();
    }

    /** La seule ligne EUR, en unités, pour l'ancien champ {@link Gmv} ; zéro sans paiement en euros. */
    public static Gmv euroOnly(List<GmvByCurrency> byCurrency) {
        GmvByCurrency eur = byCurrency.stream()
                .filter(g -> "EUR".equals(g.currency()))
                .findFirst()
                .orElse(new GmvByCurrency("EUR", 0L, 0L, 0L, 0L));
        return new Gmv(units(eur.escrowHeldCents()), units(eur.releasedCents()),
                units(eur.refundedCents()), units(eur.commissionCents()));
    }

    private static PaymentVolumeRow row(List<PaymentVolumeRow> rows, String currency, PaymentStatus status) {
        return rows.stream()
                .filter(r -> currency.equals(r.currency()) && r.status() == status)
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal amount(List<PaymentVolumeRow> rows, String currency, PaymentStatus status) {
        PaymentVolumeRow r = row(rows, currency, status);
        return r == null ? BigDecimal.ZERO : nz(r.amount());
    }

    private static BigDecimal commission(List<PaymentVolumeRow> rows, String currency, PaymentStatus status) {
        PaymentVolumeRow r = row(rows, currency, status);
        return r == null ? BigDecimal.ZERO : nz(r.commission());
    }

    private static BigDecimal refunded(List<PaymentVolumeRow> rows, String currency, PaymentStatus status) {
        PaymentVolumeRow r = row(rows, currency, status);
        return r == null ? BigDecimal.ZERO : nz(r.refunded());
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long cents(BigDecimal amount) {
        return AdminWalletResponse.toCents(amount);
    }

    private static BigDecimal units(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
