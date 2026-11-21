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
}
