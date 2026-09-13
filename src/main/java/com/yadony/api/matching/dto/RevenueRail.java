package com.yadony.api.matching.dto;

import com.yadony.api.payments.PaymentRail;

/** Rail d'une ligne de revenu, tel que l'application le libelle (Carte, Mobile money, Espèces). */
public enum RevenueRail {
    CARD,
    MOBILE_MONEY,
    CASH;

    public static RevenueRail fromPaymentRail(PaymentRail rail) {
        return rail == PaymentRail.PAWAPAY ? MOBILE_MONEY : CARD;
    }
}
