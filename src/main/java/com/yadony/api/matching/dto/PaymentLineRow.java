package com.yadony.api.matching.dto;

import com.yadony.api.payments.PaymentRail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un paiement libéré (carte ou mobile money) vu comme une ligne de revenu du
 * voyageur, dans la devise du paiement.
 *
 * <p>Le trajet vient du bid, sinon de l'annonce liée au fil de négociation.
 * Un paiement de fil sans trajet lié n'a ni {@code tripId} ni
 * {@code departureDate} : les villes et le poids viennent alors de la demande
 * de colis, et le service retombe sur {@code paidAt} pour dater la ligne.
 */
public record PaymentLineRow(
        UUID tripId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        LocalDateTime paidAt,
        BigDecimal weightKg,
        PaymentRail rail,
        String currency,
        BigDecimal amount
) {}
