package com.yadony.api.payments.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Dédommagement d'annulation réglé et pas encore versé au voyageur, vu du paquet {@code payments}.
 *
 * @param feeId           ligne de la retenue
 * @param bidId           colis annulé
 * @param travelerId      voyageur à dédommager
 * @param travelerNet     net à lui verser, dans {@code currency} (jamais négatif, 0 possible sur un petit colis)
 * @param currency        devise du paiement remboursé (code ISO à 3 lettres)
 * @param status          {@link Status#RETAINED} (aucun essai abouti) ou {@link Status#PAYOUT_PENDING} (essai en échec)
 * @param payoutRearmedAt relance du versement par un administrateur, ou {@code null} : seuls les versements mobile
 *                        money échoués après cette date comptent pour le plafond d'essais automatiques
 */
public record PayableCancellationFee(UUID feeId, UUID bidId, UUID travelerId, BigDecimal travelerNet,
                                     String currency, Status status, Instant payoutRearmedAt) {

    /** Ligne jamais relancée par un administrateur. */
    public PayableCancellationFee(UUID feeId, UUID bidId, UUID travelerId, BigDecimal travelerNet, String currency,
                                  Status status) {
        this(feeId, bidId, travelerId, travelerNet, currency, status, null);
    }

    public enum Status {
        RETAINED,
        PAYOUT_PENDING
    }
}
