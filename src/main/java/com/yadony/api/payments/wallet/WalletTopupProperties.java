package com.yadony.api.payments.wallet;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pages du portail PRO vers lesquelles Stripe Checkout renvoie après une
 * recharge du portefeuille par carte (succès ou abandon).
 *
 * <p>Détecté automatiquement : {@code @ConfigurationPropertiesScan} est actif
 * sur {@code YadonyBackApplication}.
 */
@ConfigurationProperties(prefix = "yadony.wallet.topup")
public record WalletTopupProperties(
        String checkoutSuccessUrl,
        String checkoutCancelUrl
) {}
