package com.yadony.api.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class PawapayRefundFundsTest {

    private final PawapayClient client = mock(PawapayClient.class);
    private final PawapayFeeTable table = new PawapayFeeTable(
            new PawapayFeeTable.Rate(new BigDecimal("2.5"), new BigDecimal("2.0")),
            Map.of("MTN_MOMO_CIV", new PawapayFeeTable.Rate(new BigDecimal("1.8"), new BigDecimal("1.3"))));
    private final PawapayRefundFunds funds = new PawapayRefundFunds(client, table, new BigDecimal("1.0"));

    @Test
    void balance_retrouveLeWalletDuPaysAlpha2EtDeLaDevise() {
        when(client.walletBalances()).thenReturn(Arrays.asList(
                null,
                new PawapayWalletBalance("SEN", "XOF", new BigDecimal("9000")),
                new PawapayWalletBalance("CIV", "XAF", new BigDecimal("1")),
                new PawapayWalletBalance("CIV", "XOF", null),
                new PawapayWalletBalance("CIV", "XOF", new BigDecimal("487.50"))));

        assertThat(funds.balance("ci", "XOF")).contains(new BigDecimal("487.50"));
        assertThat(funds.balance("SN", "xof")).contains(new BigDecimal("9000"));
        assertThat(funds.balance("CM", "XAF")).isEmpty();
    }

    @Test
    void balance_illisible_rendVide() {
        when(client.walletBalances()).thenThrow(new RestClientException("timeout"));
        assertThat(funds.balance("CI", "XOF")).isEmpty();

        assertThat(funds.balance(null, "XOF")).isEmpty();
        assertThat(funds.balance("CI", null)).isEmpty();
    }

    @Test
    void balance_reponseNulle_rendVide() {
        when(client.walletBalances()).thenReturn(null);
        assertThat(funds.balance("CI", "XOF")).isEmpty();
    }

    @Test
    void tauxEtMontantSur_parOperateur() {
        assertThat(funds.ratePercent("ORANGE_CIV")).isEqualByComparingTo("3.0");
        assertThat(funds.ratePercent("MTN_MOMO_CIV")).isEqualByComparingTo("2.3");
        assertThat(funds.maxSafe(new BigDecimal("487.50"), "ORANGE_CIV", "XOF")).isEqualByComparingTo("473");
        assertThat(funds.topUpNeeded(new BigDecimal("477"), new BigDecimal("487.50"), "ORANGE_CIV", "XOF"))
                .isEqualByComparingTo("4");
        verify(client, never()).walletBalances();
    }

    @Test
    void margeNulle_seulLeTauxDeRemboursement() {
        PawapayRefundFunds noMargin = new PawapayRefundFunds(client, table, null);
        assertThat(noMargin.ratePercent("ORANGE_CIV")).isEqualByComparingTo("2.0");
        assertThat(List.of(noMargin.maxSafe(new BigDecimal("487.50"), "ORANGE_CIV", "XOF")))
                .singleElement().satisfies(v -> assertThat(v).isEqualByComparingTo("477"));
    }
}
