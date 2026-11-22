package com.yadony.api.payments.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CancellationFeeCalculatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertAmount(BigDecimal actual, String expected, int scale) {
        assertThat(actual.scale()).isEqualTo(scale);
        assertThat(actual).isEqualByComparingTo(expected);
    }

    @Test
    void nominalCase() {
        var b = CancellationFeeCalculator.compute(bd("100"), bd("0.30"), bd("30"), bd("1.75"), bd("0.05"), 2);
        assertAmount(b.grossFee(), "30.00", 2);
        assertAmount(b.refundFees(), "1.75", 2);
        assertAmount(b.commission(), "1.41", 2);
        assertAmount(b.travelerNet(), "26.84", 2);
        assertAmount(b.senderRefund(), "70.00", 2);
    }

    @Test
    void plafondApplied() {
        var b = CancellationFeeCalculator.compute(bd("400"), bd("0.30"), bd("30"), bd("0"), bd("0.05"), 2);
        assertAmount(b.grossFee(), "30.00", 2);
        assertAmount(b.senderRefund(), "370.00", 2);
    }

    @Test
    void refundFeesAboveGross_travelerNetIsZero() {
        var b = CancellationFeeCalculator.compute(bd("10"), bd("0.10"), bd("30"), bd("1.75"), bd("0.05"), 2);
        assertAmount(b.grossFee(), "1.00", 2);
        assertAmount(b.commission(), "0.00", 2);
        assertAmount(b.travelerNet(), "0.00", 2);
        assertAmount(b.senderRefund(), "9.00", 2);
    }

    @Test
    void commissionZero_travelerGetsAllAfterFees() {
        var b = CancellationFeeCalculator.compute(bd("100"), bd("0.30"), bd("30"), bd("1.75"), bd("0"), 2);
        assertAmount(b.commission(), "0.00", 2);
        assertAmount(b.travelerNet(), "28.25", 2);
    }

    @Test
    void xofScaleZero_roundsHalfUp() {
        // 10 001 x 0,30 = 3000,3 -> 3000 ; frais 100 -> 2900 ; commission 5 % = 145
        var b = CancellationFeeCalculator.compute(bd("10001"), bd("0.30"), bd("20000"), bd("100"), bd("0.05"), 0);
        assertAmount(b.grossFee(), "3000", 0);
        assertAmount(b.commission(), "145", 0);
        assertAmount(b.travelerNet(), "2755", 0);
        assertAmount(b.senderRefund(), "7001", 0);

        // 2 505 x 0,30 = 751,5 -> 752 (HALF_UP)
        var h = CancellationFeeCalculator.compute(bd("2505"), bd("0.30"), bd("20000"), bd("0"), bd("0"), 0);
        assertAmount(h.grossFee(), "752", 0);
    }

    @Test
    void invariants_holdOverTable() {
        record In(String paid, String rate, String cap, String fees, String comm, int scale) {}
        List<In> table = List.of(
                new In("100", "0.30", "30", "1.75", "0.05", 2),
                new In("400", "0.30", "30", "0", "0.05", 2),
                new In("10", "0.10", "30", "1.75", "0.05", 2),
                new In("37.49", "0.25", "30", "0.33", "0.12", 2),
                new In("10001", "0.30", "20000", "100", "0.05", 0),
                new In("2505", "0.30", "20000", "5000", "0.05", 0),
                new In("99999", "0.15", "5000", "17", "0.07", 0));
        for (In i : table) {
            var b = CancellationFeeCalculator.compute(
                    bd(i.paid()), bd(i.rate()), bd(i.cap()), bd(i.fees()), bd(i.comm()), i.scale());
            BigDecimal afterFees = b.grossFee().subtract(b.refundFees()).max(BigDecimal.ZERO);
            assertThat(b.commission().add(b.travelerNet())).isEqualByComparingTo(afterFees);
            assertThat(b.senderRefund().add(b.grossFee())).isEqualByComparingTo(bd(i.paid()));
        }
    }

    @Test
    void invalidInputs_throw() {
        BigDecimal ok = bd("1");
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("0"), bd("0.3"), ok, ok, bd("0.05"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paidAmount");
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("-5"), bd("0.3"), ok, ok, bd("0.05"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("100"), bd("1.01"), ok, ok, bd("0.05"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rate");
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("100"), bd("-0.1"), ok, ok, bd("0.05"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("100"), bd("0.3"), ok, bd("-1"), bd("0.05"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refundFees");
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("100"), bd("0.3"), ok, ok, bd("-0.05"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commissionRate");
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("100"), bd("0.3"), ok, ok, bd("1.5"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commissionRate");
        assertThatThrownBy(() -> CancellationFeeCalculator.compute(bd("100"), bd("0.3"), bd("-1"), ok, bd("0.05"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capInPaymentCurrency");
    }

    // --- brut et remboursé seuls : partagés par le règlement et l'aperçu montré à l'expéditeur ---

    @Test
    void grossFeeAndSenderRefund_areExactlyThoseOfCompute() {
        record Case(String paid, String rate, String cap, int scale) {}
        for (Case c : List.of(new Case("100", "0.30", "30", 2), new Case("400", "0.30", "30", 2),
                new Case("80", "0.30", "30", 2), new Case("99.99", "0.20", "30", 2), new Case("10", "0.10", "30", 2),
                new Case("100000", "0.30", "19678", 0), new Case("65600", "0.30", "19678", 0),
                new Case("50000", "0.20", "19678", 0), new Case("100", "0", "30", 2))) {
            var full = CancellationFeeCalculator.compute(bd(c.paid()), bd(c.rate()), bd(c.cap()), bd("1.75"),
                    bd("0.05"), c.scale());

            BigDecimal gross = CancellationFeeCalculator.grossFee(bd(c.paid()), bd(c.rate()), bd(c.cap()), c.scale());
            BigDecimal refund = CancellationFeeCalculator.senderRefund(bd(c.paid()), gross, c.scale());

            assertThat(gross).as(c.toString()).isEqualTo(full.grossFee());
            assertThat(refund).as(c.toString()).isEqualTo(full.senderRefund());
        }
    }

    @Test
    void grossFee_rateZero_isZeroAtTheCurrencyScale() {
        assertAmount(CancellationFeeCalculator.grossFee(bd("100"), bd("0"), bd("30"), 2), "0.00", 2);
        assertAmount(CancellationFeeCalculator.senderRefund(bd("100"), bd("0.00"), 2), "100.00", 2);
    }

    @Test
    void grossFee_invalidInputs_throw() {
        assertThatThrownBy(() -> CancellationFeeCalculator.grossFee(bd("0"), bd("0.3"), bd("30"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paidAmount");
        assertThatThrownBy(() -> CancellationFeeCalculator.grossFee(bd("100"), bd("1.5"), bd("30"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rate");
        assertThatThrownBy(() -> CancellationFeeCalculator.grossFee(bd("100"), bd("0.3"), bd("-1"), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capInPaymentCurrency");
        assertThatThrownBy(() -> CancellationFeeCalculator.grossFee(bd("100"), bd("0.3"), bd("30"), -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scale");
    }

    @Test
    void isCapped_eurBoundary_exactlyAtCapIsNotCapped() {
        // 150 x 0,20 = 30,00 : le plafond égale le brut, il ne l'a pas limité.
        assertThat(CancellationFeeCalculator.isCapped(bd("150.00"), bd("0.20"), bd("30"), 2)).isFalse();
        assertAmount(CancellationFeeCalculator.grossFee(bd("150.00"), bd("0.20"), bd("30"), 2), "30.00", 2);
    }

    @Test
    void isCapped_eurJustAboveCap_isCapped() {
        // 150,01 x 0,20 = 30,002 -> 30,00 arrondi : pas plafonné (même montant) ; 150,10 -> 30,02 : plafonné.
        assertThat(CancellationFeeCalculator.isCapped(bd("150.01"), bd("0.20"), bd("30"), 2)).isFalse();
        assertThat(CancellationFeeCalculator.isCapped(bd("150.10"), bd("0.20"), bd("30"), 2)).isTrue();
        assertThat(CancellationFeeCalculator.isCapped(bd("200"), bd("0.20"), bd("30"), 2)).isTrue();
        assertThat(CancellationFeeCalculator.isCapped(bd("100"), bd("0.20"), bd("30"), 2)).isFalse();
    }

    @Test
    void isCapped_xofConvertedCapBoundary() {
        // Plafond converti 19 678 F : 98 390 x 0,20 = 19 678 pile ; 98 395 x 0,20 = 19 679 > plafond.
        assertThat(CancellationFeeCalculator.isCapped(bd("98390"), bd("0.20"), bd("19678"), 0)).isFalse();
        assertThat(CancellationFeeCalculator.isCapped(bd("98395"), bd("0.20"), bd("19678"), 0)).isTrue();
        assertAmount(CancellationFeeCalculator.grossFee(bd("98395"), bd("0.20"), bd("19678"), 0), "19678", 0);
    }

    @Test
    void isCapped_noFee_isFalse() {
        assertThat(CancellationFeeCalculator.isCapped(bd("200"), BigDecimal.ZERO, bd("30"), 2)).isFalse();
        assertThat(CancellationFeeCalculator.isCapped(bd("200"), bd("0.20"), BigDecimal.ZERO, 2)).isFalse();
    }
}
