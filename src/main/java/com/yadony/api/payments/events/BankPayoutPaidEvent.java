package com.yadony.api.payments.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un virement Stripe vers la banque du voyageur est arrivé ({@code payout.paid}). Publié une seule
 * fois par payout, même si l'événement Stripe est rejoué.
 *
 * @param amount montant du virement (peut regrouper plusieurs colis), dans {@code currency}
 */
public record BankPayoutPaidEvent(UUID travelerId, String payoutId, BigDecimal amount, String currency,
                                  LocalDate arrivalDate) {
}
