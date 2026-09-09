package com.yadony.api.payments.dto;

import java.math.BigDecimal;

/**
 * Ventilation mensuelle des commissions mobile money acquises, par devise.
 *
 * <p>Le mois est celui de la CRÉATION du paiement, pas du versement au voyageur : c'est la
 * date que filtre déjà la liste admin des paiements, et la seule renseignée sur toutes les
 * lignes ({@code escrow_released_at} est nul tant que la livraison n'est pas confirmée). Un
 * envoi payé fin août et livré début septembre compte donc sur août.
 *
 * @param year année de création
 * @param month mois de création, de 1 à 12
 */
public record MobileMoneyCommissionMonthRow(
        int year,
        int month,
        String currency,
        long count,
        BigDecimal gross,
        BigDecimal commission) {
}
