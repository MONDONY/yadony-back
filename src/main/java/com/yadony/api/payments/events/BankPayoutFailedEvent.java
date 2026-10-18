package com.yadony.api.payments.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un virement Stripe vers la banque du voyageur a échoué ({@code payout.failed}). Publié une seule
 * fois par payout, même si l'événement Stripe est rejoué.
 */
public record BankPayoutFailedEvent(UUID travelerId, String payoutId, String failureCode, BigDecimal amount,
                                    String currency) {
}
