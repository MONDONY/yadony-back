package com.yadony.api.voucher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Configuration du bon de réduction de commission (lot 3, remplace le crédit
 * portefeuille du parrainage). Externalisé sous {@code yadony.voucher}.
 */
@ConfigurationProperties(prefix = "yadony.voucher")
@Configuration
public class VoucherConfig {

    /** Facteur multiplicatif appliqué au taux/prélèvement (0.50 = moitié prix). */
    private BigDecimal factor = new BigDecimal("0.50");

    /** Durée de validité du bon, en mois, à compter de son octroi. */
    private int validityMonths = 6;

    public BigDecimal getFactor() { return factor; }
    public void setFactor(BigDecimal factor) { this.factor = factor; }

    public int getValidityMonths() { return validityMonths; }
    public void setValidityMonths(int validityMonths) { this.validityMonths = validityMonths; }
}
