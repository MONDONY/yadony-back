package com.yadony.api.payments.pawapay;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code yadony.pawapay.*}, toutes valeurs par variables d'environnement (voir application.yml). */
@ConfigurationProperties(prefix = "yadony.pawapay")
public record PawapayProperties(
        boolean enabled,
        String baseUrl,
        String apiToken,
        boolean callbackSignaturesRequired,
        int depositDeadlineMinutes,
        String returnBaseUrl,
        /** Modèle {@code String.format} avec le bidId, ex. {@code yadony://bids/%s/mobile-money/awaiting}. */
        String deepLinkAwaiting,
        /** Modèle {@code String.format} avec le threadId, ex. {@code yadony://negotiations/%s/mobile-money/awaiting}. */
        String deepLinkAwaitingThread,
        /** Écran de recharge du portefeuille, sans paramètre, ex. {@code yadony://payments/wallet}. */
        String deepLinkWallet,
        BalanceMin balanceMin) {

    public record BalanceMin(BigDecimal xof, BigDecimal xaf) {}
}
