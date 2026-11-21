package com.yadony.api.payments.fee;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dédommagement d'annulation réglé et pas encore versé au voyageur, vu du paquet {@code payments}.
 *
 * @param feeId       ligne de la retenue
 * @param bidId       colis annulé
 * @param travelerId  voyageur à dédommager
 * @param travelerNet net à lui verser, dans {@code currency} (jamais négatif, 0 possible sur un petit colis)
 * @param currency    devise du paiement remboursé (code ISO à 3 lettres)
 * @param status      {@link Status#RETAINED} (aucun essai abouti) ou {@link Status#PAYOUT_PENDING} (essai en échec)
 */
public record PayableCancellationFee(UUID feeId, UUID bidId, UUID travelerId, BigDecimal travelerNet,
                                     String currency, Status status) {

    public enum Status {
        RETAINED,
        PAYOUT_PENDING
    }
}
