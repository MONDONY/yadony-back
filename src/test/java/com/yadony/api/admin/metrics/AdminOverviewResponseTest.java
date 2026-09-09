package com.yadony.api.admin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.dto.PaymentVolumeRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminOverviewResponseTest {

    private static PaymentVolumeRow row(String currency, PaymentStatus status,
                                        String amount, String commission, String refunded) {
        return new PaymentVolumeRow(currency, status, bd(amount), bd(commission), bd(refunded));
    }

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @Test
    void foldByCurrency_replieLesStatutsParDevise_enCentiemes() {
        List<AdminOverviewResponse.GmvByCurrency> out = AdminOverviewResponse.foldByCurrency(List.of(
                row("XOF", PaymentStatus.RELEASED, "19800.00", "1800.00", "0"),
                row("XOF", PaymentStatus.ESCROW, "9900.00", "900.00", "0"),
                row("XAF", PaymentStatus.REFUNDED, "5000.00", "500.00", "5000.00"),
                row("EUR", PaymentStatus.RELEASED, "100.00", "12.00", "0")));

        assertThat(out).extracting(AdminOverviewResponse.GmvByCurrency::currency)
                .containsExactly("XOF", "XAF", "EUR");

        AdminOverviewResponse.GmvByCurrency xof = out.get(0);
        assertThat(xof.escrowHeldCents()).isEqualTo(990000L);
        assertThat(xof.releasedCents()).isEqualTo(1980000L);
        assertThat(xof.commissionCents()).isEqualTo(180000L);
        assertThat(xof.refundedCents()).isZero();

        AdminOverviewResponse.GmvByCurrency xaf = out.get(1);
        assertThat(xaf.refundedCents()).isEqualTo(500000L);
        assertThat(xaf.releasedCents()).isZero();
        assertThat(xaf.commissionCents()).isZero();

        assertThat(out.get(2).releasedCents()).isEqualTo(10000L);
        assertThat(out.get(2).commissionCents()).isEqualTo(1200L);
    }

    @Test
    void foldByCurrency_tolereLesSommesNulles() {
        List<AdminOverviewResponse.GmvByCurrency> out = AdminOverviewResponse.foldByCurrency(List.of(
                row("XOF", PaymentStatus.RELEASED, null, null, null),
                row("XOF", PaymentStatus.REFUNDED, null, null, null)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).releasedCents()).isZero();
        assertThat(out.get(0).commissionCents()).isZero();
        assertThat(out.get(0).refundedCents()).isZero();
    }

    @Test
    void foldByCurrency_sansLigne_listeVide() {
        assertThat(AdminOverviewResponse.foldByCurrency(List.of())).isEmpty();
    }

    @Test
    void euroOnly_neSertQueLaLigneEur_enUnites() {
        AdminOverviewResponse.Gmv gmv = AdminOverviewResponse.euroOnly(List.of(
                new AdminOverviewResponse.GmvByCurrency("XOF", 990000L, 1980000L, 0L, 180000L),
                new AdminOverviewResponse.GmvByCurrency("EUR", 123456L, 500000L, 10000L, 60000L)));

        assertThat(gmv.escrowHeld()).isEqualByComparingTo("1234.56");
        assertThat(gmv.released()).isEqualByComparingTo("5000.00");
        assertThat(gmv.refunded()).isEqualByComparingTo("100.00");
        assertThat(gmv.commission()).isEqualByComparingTo("600.00");
    }

    @Test
    void euroOnly_sansLigneEur_vautZero() {
        AdminOverviewResponse.Gmv gmv = AdminOverviewResponse.euroOnly(List.of(
                new AdminOverviewResponse.GmvByCurrency("XOF", 990000L, 1980000L, 0L, 180000L)));

        assertThat(gmv.escrowHeld()).isEqualByComparingTo("0");
        assertThat(gmv.released()).isEqualByComparingTo("0");
        assertThat(gmv.refunded()).isEqualByComparingTo("0");
        assertThat(gmv.commission()).isEqualByComparingTo("0");
    }
}
