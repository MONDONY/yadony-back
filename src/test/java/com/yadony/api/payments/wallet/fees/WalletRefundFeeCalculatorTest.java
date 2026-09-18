package com.yadony.api.payments.wallet.fees;

import com.yadony.api.payments.wallet.WalletRefundAllocation.RefundableTopup;
import com.yadony.api.payments.wallet.WalletRefundRail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletRefundFeeCalculatorTest {

    private final WalletRefundFeeCalculator.FeeSources sources = new WalletRefundFeeCalculator.FeeSources() {
        @Override
        public BigDecimal stripeFee(String paymentIntentId, BigDecimal amount, String currency) {
            return new BigDecimal("1.51");
        }

        @Override
        public BigDecimal pawapayFee(String provider, BigDecimal amount, String currency) {
            return amount.multiply(new BigDecimal("0.02"));
        }
    };

    private RefundableTopup topup(String ref, String original, String remaining) {
        WalletRefundRail rail = WalletRefundRail.of(ref);
        String provider = rail == WalletRefundRail.PAWAPAY ? "ORANGE_CIV" : null;
        return new RefundableTopup(UUID.randomUUID(), ref, new BigDecimal(original), new BigDecimal(remaining),
                BigDecimal.ZERO, rail, provider);
    }

    @Test
    void untouchedStripeTopup_paysRealFee() {
        assertThat(WalletRefundFeeCalculator.feeFor(topup("pi_1", "40.00", "40.00"), "EUR", sources))
                .isEqualByComparingTo("1.51");
    }

    @Test
    void partlySpentTopup_noFee() {
        assertThat(WalletRefundFeeCalculator.feeFor(topup("pi_1", "40.00", "35.00"), "EUR", sources))
                .isEqualByComparingTo("0");
    }

    @Test
    void pawapayFee_roundedToWholeFranc() {
        assertThat(WalletRefundFeeCalculator.feeFor(
                topup("pawapay:" + UUID.randomUUID(), "10001", "10001"), "XOF", sources))
                .isEqualByComparingTo("200");
    }

    @Test
    void feeNeverExceedsRemaining() {
        WalletRefundFeeCalculator.FeeSources huge = new WalletRefundFeeCalculator.FeeSources() {
            @Override
            public BigDecimal stripeFee(String paymentIntentId, BigDecimal amount, String currency) {
                return new BigDecimal("99");
            }

            @Override
            public BigDecimal pawapayFee(String provider, BigDecimal amount, String currency) {
                return amount;
            }
        };
        assertThat(WalletRefundFeeCalculator.feeFor(topup("pi_1", "5.00", "5.00"), "EUR", huge))
                .isEqualByComparingTo("5.00");
    }
}
