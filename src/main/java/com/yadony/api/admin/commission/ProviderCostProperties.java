package com.yadony.api.admin.commission;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Coûts des prestataires de paiement, pour le simulateur de marge uniquement (jamais pour le prix).
 * Valeurs par défaut : tarif public Stripe France vérifié le 18/10/2026 (voir application.yml).
 * Les frais pawaPay viennent de {@code yadony.pawapay.fees}.
 *
 * @param cardStandardPercent         carte EEE standard, en pour cent du brut.
 * @param cardPremiumPercent          carte premium.
 * @param cardInternationalPercent    carte internationale.
 * @param cardFixedEur                frais fixe par encaissement carte, en euros.
 * @param connectPayoutPercent        Connect : pour cent du net versé au voyageur.
 * @param connectPayoutFixedEur       Connect : frais fixe par versement, en euros.
 * @param connectActiveAccountMonthlyEur Connect : coût mensuel d'un compte actif, en euros (affiché à part).
 */
@ConfigurationProperties("yadony.costs.stripe")
public record ProviderCostProperties(BigDecimal cardStandardPercent, BigDecimal cardPremiumPercent,
                                     BigDecimal cardInternationalPercent, BigDecimal cardFixedEur,
                                     BigDecimal connectPayoutPercent, BigDecimal connectPayoutFixedEur,
                                     BigDecimal connectActiveAccountMonthlyEur) {
}
