package com.yadony.api.payments;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PriceBreakdownTest {

    @Test void modelB_net35_rate12() {
        PriceBreakdown b = PriceBreakdown.fromNet(new BigDecimal("35"), new BigDecimal("0.12"));
        assertThat(b.net()).isEqualByComparingTo("35.00");
        assertThat(b.commission()).isEqualByComparingTo("4.20");
        assertThat(b.gross()).isEqualByComparingTo("39.20");
    }

    /** Le taux peut avoir plus de 2 décimales (ex. 12.3456789%) : les montants restent
     *  arrondis au centime et gross - commission redonne exactement le net. */
    @Test void withFractionalRate_amountsStayConsistent() {
        PriceBreakdown b = PriceBreakdown.fromNet(new BigDecimal("35"), new BigDecimal("0.123456789"));
        assertThat(b.commission()).isEqualByComparingTo("4.32");
        assertThat(b.gross()).isEqualByComparingTo("39.32");
        assertThat(b.gross().subtract(b.commission())).isEqualByComparingTo(b.net());
    }
}
