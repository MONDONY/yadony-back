package com.yadony.api.payments.bankpayout;

import java.time.LocalDate;

/**
 * État d'un payout Stripe d'un compte connecté, lu dans un webhook {@code payout.*} ou par le
 * rattrapage ({@code GET /v1/payouts}).
 *
 * @param status      statut brut Stripe : {@code pending}, {@code in_transit}, {@code paid},
 *                    {@code failed}, {@code canceled}
 * @param arrivalDate date d'arrivée prévue (ou effective une fois {@code paid}), UTC
 * @param amountMinor montant en unités mineures de {@code currency}
 * @param created     création du payout (epoch, secondes), {@code null} si inconnue
 */
public record PayoutSnapshot(String id, String status, LocalDate arrivalDate, Long amountMinor, String currency,
                             String failureCode, Boolean automatic, Long created) {
}
