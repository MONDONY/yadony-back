package com.yadony.api.payments.cash;

import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WalletCommissionCollectorTest {

    private final UUID traveler = UUID.randomUUID();
    private final UUID bid = UUID.randomUUID();
    private WalletService walletService;
    private ExchangeRateService exchangeRateService;
    private WalletCommissionCollector collector;

    @BeforeEach
    void setUp() {
        walletService = mock(WalletService.class);
        exchangeRateService = mock(ExchangeRateService.class);
        collector = new WalletCommissionCollector(walletService, exchangeRateService);
    }

    private void balances(String xof, String eur) {
        when(walletService.getBalance(traveler, "XOF")).thenReturn(new BigDecimal(xof));
        when(walletService.getBalance(traveler, "EUR")).thenReturn(new BigDecimal(eur));
    }

    @Test
    void sameCurrencySufficient_singleLine() {
        when(walletService.getBalance(traveler, "EUR")).thenReturn(new BigDecimal("50.00"));

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("12.00"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.fromBidWallet()).isEqualByComparingTo("12.00");
        assertThat(s.remainingBid()).isEqualByComparingTo("0");
        assertThat(s.appliedRate()).isNull();
        verify(walletService).debit(traveler, "EUR", new BigDecimal("12.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
        verify(exchangeRateService, never()).convert(any(), any(), any());
    }

    @Test
    void bidWalletCoversEverything_noConversion() {
        balances("10000", "1.33");
        when(exchangeRateService.convert(new BigDecimal("1050"), "XOF", "EUR")).thenReturn(new BigDecimal("1.60"));

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.fromBidWallet()).isEqualByComparingTo("1050");
        assertThat(s.remainingBid()).isEqualByComparingTo("0");
        assertThat(s.remainingActive()).isEqualByComparingTo("0");
        verify(walletService).debit(traveler, "XOF", new BigDecimal("1050"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
        // La conversion sert à l'affichage (commission totale en devise active), jamais au débit.
        verify(walletService, never()).debit(any(UUID.class), eq("EUR"), any(BigDecimal.class),
                any(WalletTransactionType.class), any(UUID.class), any(), any(), any());
        assertThat(s.commissionInActive()).isEqualByComparingTo("1.60");
    }

    @Test
    void commissionInActive_sameCurrency_isTheCommission() {
        when(walletService.getBalance(traveler, "EUR")).thenReturn(new BigDecimal("50.00"));

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("12.00"));

        assertThat(s.commissionInActive()).isEqualByComparingTo("12.00");
        verify(exchangeRateService, never()).convert(any(), any(), any());
    }

    @Test
    void commissionInActive_differentCurrencies_isTheWholeCommissionConverted() {
        balances("600", "0.10");
        when(exchangeRateService.convert(new BigDecimal("1050"), "XOF", "EUR")).thenReturn(new BigDecimal("1.60"));
        when(exchangeRateService.convert(new BigDecimal("450"), "XOF", "EUR")).thenReturn(new BigDecimal("0.69"));

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));

        assertThat(s.covered()).isFalse();
        assertThat(s.commissionInActive()).isEqualByComparingTo("1.60");
        assertThat(s.remainingActive()).isEqualByComparingTo("0.69");
    }

    @Test
    void partialBidWallet_completedByActiveWallet_twoLines() {
        balances("600", "1.33");
        when(exchangeRateService.convert(new BigDecimal("1050"), "XOF", "EUR")).thenReturn(new BigDecimal("1.60"));
        when(exchangeRateService.convert(new BigDecimal("450"), "XOF", "EUR")).thenReturn(new BigDecimal("0.69"));

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.fromBidWallet()).isEqualByComparingTo("600");
        assertThat(s.remainingBid()).isEqualByComparingTo("450");
        assertThat(s.remainingActive()).isEqualByComparingTo("0.69");
        assertThat(s.appliedRate()).isEqualByComparingTo("0.001533");
        verify(walletService).debit(traveler, "XOF", new BigDecimal("600"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
        verify(walletService).debit(traveler, "EUR", new BigDecimal("0.69"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid, "XOF", new BigDecimal("450"), s.appliedRate());
    }

    @Test
    void partialBidWallet_activeInsufficient_nothingDebited() {
        balances("600", "0.50");
        when(exchangeRateService.convert(new BigDecimal("1050"), "XOF", "EUR")).thenReturn(new BigDecimal("1.60"));
        when(exchangeRateService.convert(new BigDecimal("450"), "XOF", "EUR")).thenReturn(new BigDecimal("0.69"));

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));

        assertThat(s.covered()).isFalse();
        assertThat(s.activeBalance()).isEqualByComparingTo("0.50");
        assertThatThrownBy(() -> collector.executeForBid(s, traveler, bid))
                .isInstanceOf(IllegalStateException.class);
        verify(walletService, never()).debit(any(), any(), any(), any(), any());
    }

    @Test
    void emptyBidWallet_everythingOnActive_currentBehaviour() {
        balances("0", "5.00");
        when(exchangeRateService.convert(new BigDecimal("1050"), "XOF", "EUR")).thenReturn(new BigDecimal("1.60"));

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.fromBidWallet()).isEqualByComparingTo("0");
        assertThat(s.remainingActive()).isEqualByComparingTo("1.60");
        verify(walletService, never()).debit(eq(traveler), eq("XOF"), any(), any(), eq(bid));
        verify(walletService).debit(traveler, "EUR", new BigDecimal("1.60"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid, "XOF", new BigDecimal("1050"), s.appliedRate());
    }

    @Test
    void zeroCommission_singleZeroDebitOnActive() {
        balances("0", "0");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", BigDecimal.ZERO);
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        verify(walletService).debit(traveler, "EUR", BigDecimal.ZERO,
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
        verifyNoMoreInteractions(exchangeRateService);
    }

    @Test
    void negotiationVariant_usesPrefixedIdempotencyKeys() {
        balances("600", "1.33");
        when(exchangeRateService.convert(new BigDecimal("1050"), "XOF", "EUR")).thenReturn(new BigDecimal("1.60"));
        when(exchangeRateService.convert(new BigDecimal("450"), "XOF", "EUR")).thenReturn(new BigDecimal("0.69"));
        String ref = "thread-1";

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForNegotiation(s, traveler, ref, "nego_commission_wallet_thread-1");

        verify(walletService).debit(traveler, "XOF", new BigDecimal("600"),
                WalletTransactionType.COMMISSION_DEDUCTED, ref, "nego_commission_wallet_thread-1");
        verify(walletService).debit(traveler, "EUR", new BigDecimal("0.69"),
                WalletTransactionType.COMMISSION_DEDUCTED, ref, "nego_commission_wallet_thread-1_active",
                "XOF", new BigDecimal("450"), s.appliedRate());
    }

    @Test
    void currencyCodesAreNormalized() {
        when(walletService.getBalance(traveler, "XOF")).thenReturn(new BigDecimal("10000"));
        when(walletService.getBalance(traveler, "EUR")).thenReturn(new BigDecimal("0"));
        when(exchangeRateService.convert(new BigDecimal("1050"), "XOF", "EUR")).thenReturn(new BigDecimal("1.60"));

        CommissionSplit s = collector.plan(traveler, " xof ", "eur", new BigDecimal("1050"));

        assertThat(s.bidCurrency()).isEqualTo("XOF");
        assertThat(s.activeCurrency()).isEqualTo("EUR");
        assertThat(s.covered()).isTrue();
    }
}
