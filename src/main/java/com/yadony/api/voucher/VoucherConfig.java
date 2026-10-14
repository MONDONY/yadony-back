package com.yadony.api.voucher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Configuration du bon de réduction de commission (lot 3). Externalisé sous
 * {@code yadony.voucher}. Depuis V321 le parrainage n'octroie plus de bon : les bons
 * déjà octroyés gardent leur facteur et leur expiration propres, seul le facteur
 * courant reste affiché sur l'écran Parrainage ({@code MyReferralResponse.voucherFactor}).
 */
@ConfigurationProperties(prefix = "yadony.voucher")
@Configuration
public class VoucherConfig {

    /** Facteur multiplicatif appliqué au taux/prélèvement (0.50 = moitié prix). */
    private BigDecimal factor = new BigDecimal("0.50");

    public BigDecimal getFactor() { return factor; }
    public void setFactor(BigDecimal factor) { this.factor = factor; }
}
