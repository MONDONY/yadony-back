package com.yadony.api.payments.overview;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

/**
 * Traduit une {@link MoneyRow} en état normalisé, condition et dates de libération.
 *
 * <p>Fonction pure, sans accès base : l'ordre des règles reproduit celui des gardes de
 * {@code payments.DeliveryEventListener#release} (litige / chargeback, remboursement partiel,
 * bénéficiaire gelé, puis libération) et de {@code cancellation.UnclaimedParcelScheduler}
 * (garde échue sans aucun litige). Une seule date de libération est réellement calculable dans le
 * système : la fin de garde « destinataire absent » ({@code cancellations.hold_until}). Le
 * scheduler J+48 ({@code EscrowScheduler}) ne libère rien, il alerte un admin : il ne donne donc
 * aucune date.
 */
final class MoneyStateResolver {

    /** Statuts de colis « chez le voyageur » — miroir de {@code BidStatus.EN_ROUTE}. */
    private static final Set<String> EN_ROUTE = Set.of("HANDED_OVER", "IN_TRANSIT", "ARRIVED");

    private MoneyStateResolver() {
    }

    /** Résultat de la résolution d'une ligne. */
    record Resolution(MoneyState state, ReleaseCondition condition, OffsetDateTime releaseAt,
                      OffsetDateTime settledAt, BigDecimal amount, String currency) {
    }

    /**
     * @return vide quand la ligne n'a rien à montrer de ce côté (paiement remboursé vu du voyageur)
     */
    static Optional<Resolution> resolve(MoneyRow row) {
        if (!row.hasPayment()) {
            return Optional.of(new Resolution(MoneyState.CASH, ReleaseCondition.CASH_IN_PERSON,
                    null, null, null, upper(row.bidCurrency())));
        }
        String currency = upper(row.paymentCurrency());
        BigDecimal amount = amountFor(row);
        String status = row.paymentStatus();
        if ("REFUNDED".equals(status)) {
            if (row.role() == MoneyRole.TRAVELER) {
                return Optional.empty();
            }
            return Optional.of(new Resolution(MoneyState.REFUNDED_RECENTLY, ReleaseCondition.REFUNDED,
                    null, utc(row.paymentUpdatedAt()), row.amount(), currency));
        }
        if ("RELEASED".equals(status)) {
            if (row.openPayouts() > 0) {
                return Optional.of(new Resolution(MoneyState.PAYOUT_IN_PROGRESS,
                        ReleaseCondition.PAYOUT_PROCESSING, null, utc(row.escrowReleasedAt()), amount, currency));
            }
            if (row.role() == MoneyRole.SENDER) {
                return Optional.empty();
            }
            return Optional.of(new Resolution(MoneyState.RELEASED_RECENTLY, ReleaseCondition.RELEASED,
                    null, utc(row.escrowReleasedAt()), amount, currency));
        }
        // ESCROW : l'argent est encore chez Yadony (ou chez son prestataire).
        return Optional.of(escrow(row, amount, currency));
    }

    private static Resolution escrow(MoneyRow row, BigDecimal amount, String currency) {
        if (row.openRefunds() > 0) {
            return new Resolution(MoneyState.REFUND_PENDING, ReleaseCondition.REFUND_PROCESSING,
                    null, null, amount, currency);
        }
        if (row.chargeback() || row.openDisputes() > 0) {
            return new Resolution(MoneyState.IN_DISPUTE, ReleaseCondition.ADMIN_DECISION,
                    null, null, amount, currency);
        }
        boolean partiallyRefunded = row.refundedAmount() != null && row.refundedAmount().signum() > 0;
        if (row.payoutHeldAt() != null || partiallyRefunded || row.allDisputes() > 0) {
            // Litige tranché mais paiement encore ESCROW : la décision admin (partage,
            // remboursement, libération) est en cours d'exécution, jamais un versement automatique.
            return new Resolution(MoneyState.ON_HOLD, ReleaseCondition.ADMIN_REVIEW,
                    null, null, amount, currency);
        }
        if (row.holdUntil() != null) {
            return new Resolution(MoneyState.RELEASE_SCHEDULED,
                    ReleaseCondition.AUTO_RELEASE_AFTER_HOLD_IF_NO_DISPUTE,
                    row.holdUntil().withOffsetSameInstant(ZoneOffset.UTC), null, amount, currency);
        }
        if ("COMPLETED".equals(row.bidStatus())) {
            // Livraison confirmée, libération asynchrone pas encore passée (ou compte de
            // versement à régulariser) : l'argent part, il n'y a plus de condition côté colis.
            return new Resolution(MoneyState.PAYOUT_IN_PROGRESS, ReleaseCondition.PAYOUT_PROCESSING,
                    null, null, amount, currency);
        }
        if (row.bidStatus() != null && EN_ROUTE.contains(row.bidStatus())) {
            return new Resolution(MoneyState.AWAITING_DELIVERY_CONFIRMATION,
                    ReleaseCondition.ON_DELIVERY_CONFIRMATION, null, null, amount, currency);
        }
        return new Resolution(MoneyState.ESCROWED, ReleaseCondition.ON_DELIVERY_CONFIRMATION,
                null, null, amount, currency);
    }

    /**
     * Voyageur : net estimé = payé − commission (formule de {@code DeliveryEventListener#releaseV2},
     * hors bon de commission éventuel, connu seulement au versement). Expéditeur : payé − déjà
     * remboursé.
     */
    static BigDecimal amountFor(MoneyRow row) {
        BigDecimal paid = row.amount() == null ? BigDecimal.ZERO : row.amount();
        if (row.role() == MoneyRole.TRAVELER) {
            BigDecimal commission = row.commissionAmount() == null ? BigDecimal.ZERO : row.commissionAmount();
            return paid.subtract(commission).max(BigDecimal.ZERO);
        }
        BigDecimal refunded = row.refundedAmount() == null ? BigDecimal.ZERO : row.refundedAmount();
        return paid.subtract(refunded).max(BigDecimal.ZERO);
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String upper(String currency) {
        return currency == null ? null : currency.toUpperCase(java.util.Locale.ROOT);
    }
}
