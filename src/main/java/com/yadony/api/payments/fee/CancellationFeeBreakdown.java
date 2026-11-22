package com.yadony.api.payments.fee;

import java.math.BigDecimal;

/**
 * Ventilation du dédommagement retenu lors de l'annulation d'une réservation par l'expéditeur.
 *
 * @param grossFee      dédommagement brut retenu sur le montant payé
 * @param refundFees    frais de remboursement du prestataire de paiement, pris sur le dédommagement
 * @param commission    commission de la plateforme sur le dédommagement net des frais
 * @param travelerNet   montant net versé au voyageur
 * @param senderRefund  montant remboursé à l'expéditeur
 */
public record CancellationFeeBreakdown(
        BigDecimal grossFee,
        BigDecimal refundFees,
        BigDecimal commission,
        BigDecimal travelerNet,
        BigDecimal senderRefund) {}
