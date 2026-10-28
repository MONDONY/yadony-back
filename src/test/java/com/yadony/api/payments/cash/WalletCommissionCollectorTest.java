package com.yadony.api.payments.cash;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateFreshness;
import com.yadony.api.payments.currency.ExchangeRateLookup;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.wallet.InsufficientWalletBalanceException;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Répartition d'une commission espèces sur les portefeuilles du voyageur. Les conversions passent par
 * un VRAI {@link ExchangeRateService} à taux fixes (1 € = 655,957 F CFA, 1,08 $, 0,86 £, 1,47 CAD), pour
 * que les montants attendus soient ceux que la production calculerait, arrondis compris.
 */
class WalletCommissionCollectorTest {

    private final UUID traveler = UUID.randomUUID();
    private final UUID bid = UUID.randomUUID();
    private WalletService walletService;
    private ExchangeRateLookup lookup;
    private ExchangeRateFreshness freshness;
    private WalletCommissionCollector collector;

    @BeforeEach
    void setUp() {
        walletService = mock(WalletService.class);
        lookup = mock(ExchangeRateLookup.class);
        when(lookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        when(lookup.unitsPerEur("XAF")).thenReturn(new BigDecimal("655.957"));
        when(lookup.unitsPerEur("USD")).thenReturn(new BigDecimal("1.08"));
        when(lookup.unitsPerEur("GBP")).thenReturn(new BigDecimal("0.86"));
        when(lookup.unitsPerEur("CAD")).thenReturn(new BigDecimal("1.47"));
        freshness = mock(ExchangeRateFreshness.class);
        collector = new WalletCommissionCollector(walletService, new ExchangeRateService(lookup), freshness);
    }

    /**
     * Pose les portefeuilles du voyageur : paires (devise, solde). Une devise absente a un solde
     * nul (le portefeuille n'existe pas). {@code getBalanceForUpdate} ne rend jamais {@code null}.
     */
    private void wallets(String... currencyAndBalance) {
        when(walletService.getBalanceForUpdate(eq(traveler), anyString())).thenReturn(BigDecimal.ZERO);
        List<WalletAccountEntity> all = new ArrayList<>();
        for (int i = 0; i < currencyAndBalance.length; i += 2) {
            String currency = currencyAndBalance[i];
            BigDecimal balance = new BigDecimal(currencyAndBalance[i + 1]);
            when(walletService.getBalanceForUpdate(traveler, currency)).thenReturn(balance);
            all.add(wallet(currency, currencyAndBalance[i + 1]));
        }
        when(walletService.getAllBalances(traveler)).thenReturn(all);
    }

    private static WalletAccountEntity wallet(String currency, String balance) {
        var w = new WalletAccountEntity();
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    /** Matcher de montant indépendant de l'échelle (« 3.00 » égale « 3 »). */
    private static BigDecimal amount(String value) {
        return argThat(actual -> actual != null && actual.compareTo(new BigDecimal(value)) == 0);
    }

    private static BigDecimal rateNear(String value) {
        BigDecimal expected = new BigDecimal(value);
        return argThat(actual -> actual != null && actual.compareTo(expected) == 0);
    }

    // ─── Verrous ──────────────────────────────────────────────────────────────

    @Test
    void plan_locksEveryWalletWithBalance_inAlphabeticalOrder_neverUnlockedRead() {
        // Le verrou est pris via getBalanceForUpdate (jamais getBalance), dans l'ordre alphabétique,
        // sur tous les portefeuilles utiles : sinon deux règlements croisés du même voyageur
        // pourraient s'interbloquer.
        wallets("XOF", "600", "EUR", "1.33", "USD", "5.00");

        collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));

        InOrder order = inOrder(walletService);
        order.verify(walletService).getBalanceForUpdate(traveler, "EUR");
        order.verify(walletService).getBalanceForUpdate(traveler, "USD");
        order.verify(walletService).getBalanceForUpdate(traveler, "XOF");
        verify(walletService, never()).getBalance(any(), any());
    }

    @Test
    void plan_sameLockOrderWhateverTheDirection() {
        wallets("XOF", "600", "EUR", "1.33");

        collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.plan(traveler, "EUR", "XOF", new BigDecimal("12.00"));

        InOrder order = inOrder(walletService);
        order.verify(walletService).getBalanceForUpdate(traveler, "EUR");
        order.verify(walletService).getBalanceForUpdate(traveler, "XOF");
        order.verify(walletService).getBalanceForUpdate(traveler, "EUR");
        order.verify(walletService).getBalanceForUpdate(traveler, "XOF");
    }

    @Test
    void frozenBidWallet_treatedAsEmpty_everythingOnAnotherWallet_andNeverLocked() {
        // Portefeuille XOF gelé par une demande de remboursement : debit le refuserait (422), il
        // compte donc pour zéro et n'est pas verrouillé ; tout part sur l'autre portefeuille.
        wallets("EUR", "5.00");
        when(walletService.isFrozen(traveler, "XOF")).thenReturn(true);

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.bidWalletBalance()).isEqualByComparingTo("0");
        assertThat(s.fromBidWallet()).isEqualByComparingTo("0");
        assertThat(s.excluded()).containsEntry("XOF", "frozen");
        verify(walletService, never()).getBalanceForUpdate(traveler, "XOF");
        verify(walletService, never()).debit(eq(traveler), eq("XOF"), any(), any(), eq(bid));
        verify(walletService).debit(eq(traveler), eq("EUR"), amount("1.60"),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(bid), eq("XOF"), amount("1050"), any());
    }

    @Test
    void frozenActiveWallet_sameCurrency_notCovered() {
        wallets();
        when(walletService.isFrozen(traveler, "EUR")).thenReturn(true);

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("12.00"));

        assertThat(s.covered()).isFalse();
        assertThat(s.activeBalance()).isEqualByComparingTo("0");
        assertThat(s.lines()).isEmpty();
        verify(walletService, never()).getBalanceForUpdate(any(), any());
    }

    // ─── Répartition ──────────────────────────────────────────────────────────

    @Test
    void sameCurrencySufficient_singleLine() {
        wallets("EUR", "50.00");

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("12.00"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.fromBidWallet()).isEqualByComparingTo("12.00");
        assertThat(s.remainingBid()).isEqualByComparingTo("0");
        assertThat(s.appliedRate()).isNull();
        assertThat(s.lines()).hasSize(1);
        verify(walletService).debit(traveler, "EUR", new BigDecimal("12.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
    }

    @Test
    void bidWalletCoversEverything_noConversion_noOtherWalletLocked() {
        wallets("XOF", "2000", "EUR", "9.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.lines()).hasSize(1);
        assertThat(s.lines().get(0).walletCurrency()).isEqualTo("XOF");
        assertThat(s.lines().get(0).appliedRate()).isNull();
        verify(walletService).debit(traveler, "XOF", new BigDecimal("1050"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
        verify(walletService, never()).debit(eq(traveler), eq("EUR"), any(), any(), any(UUID.class),
                any(), any(), any());
    }

    @Test
    void commissionInActive_sameCurrency_isTheCommission() {
        wallets("EUR", "50.00");

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("12.00"));

        assertThat(s.commissionInActive()).isEqualByComparingTo("12.00");
        assertThat(s.sameCurrency()).isTrue();
    }

    @Test
    void commissionInActive_differentCurrencies_isTheWholeCommissionConverted() {
        wallets("XOF", "600", "EUR", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));

        // Même quand le portefeuille du colis ne couvre qu'une partie : c'est le TOTAL, pour l'affichage.
        assertThat(s.commissionInActive()).isEqualByComparingTo("1.60");
    }

    @Test
    void partialBidWallet_completedByAnotherWallet_twoLines() {
        // Colis XOF 1050 : portefeuille XOF 600, reste 450 XOF = 0,69 € (0,686 arrondi) sur les euros.
        wallets("XOF", "600", "EUR", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("XOF", "EUR");
        CommissionLine eur = s.lines().get(1);
        assertThat(eur.debitAmount()).isEqualByComparingTo("0.69");
        assertThat(eur.coveredInBid()).isEqualByComparingTo("450");
        assertThat(eur.appliedRate()).isEqualByComparingTo("0.001533");
        verify(walletService).debit(traveler, "XOF", new BigDecimal("600"),
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
        verify(walletService).debit(eq(traveler), eq("EUR"), amount("0.69"),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(bid), eq("XOF"), amount("450"),
                rateNear("0.001533"));
    }

    @Test
    void noEuros_dollarsDrainedThenCfa_forAnEuroCommission() {
        // Commission 4,00 €, aucun euro, 3,00 $ et 1 500 F CFA : aucun ne suffit seul, ensemble oui.
        // Dollars d'abord (≈ 2,77 € contre ≈ 2,28 €), vidés exactement ; F CFA convertis au reste.
        wallets("USD", "3.00", "XOF", "1500");

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("4.00"));
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("USD", "XOF");
        CommissionLine usd = s.lines().get(0);
        assertThat(usd.debitAmount()).isEqualByComparingTo("3.00");
        assertThat(usd.coveredInBid()).isEqualByComparingTo("2.77");
        assertThat(usd.appliedRate()).isEqualByComparingTo("1.083032");
        CommissionLine xof = s.lines().get(1);
        assertThat(xof.debitAmount()).isEqualByComparingTo("807");
        assertThat(xof.coveredInBid()).isEqualByComparingTo("1.23");
        assertThat(xof.appliedRate()).isEqualByComparingTo("656.097561");
        // Les montants couverts réunis donnent exactement la commission.
        assertThat(usd.coveredInBid().add(xof.coveredInBid())).isEqualByComparingTo("4.00");
        verify(walletService).debit(eq(traveler), eq("USD"), amount("3.00"),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(bid), eq("EUR"), amount("2.77"),
                rateNear("1.083032"));
        verify(walletService).debit(eq(traveler), eq("XOF"), amount("807"),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(bid), eq("EUR"), amount("1.23"),
                rateNear("656.097561"));
    }

    @Test
    void theActiveCurrencyHasNoSpecialRank_sameSplitWhateverTheDisplayCurrency() {
        // Changer la devise d'affichage ne change jamais d'où part l'argent.
        wallets("USD", "3.00", "XOF", "1500");

        CommissionSplit activeEur = collector.plan(traveler, "EUR", "EUR", new BigDecimal("4.00"));
        CommissionSplit activeXof = collector.plan(traveler, "EUR", "XOF", new BigDecimal("4.00"));

        assertThat(activeXof.lines()).isEqualTo(activeEur.lines());
        assertThat(activeXof.lines()).extracting(CommissionLine::walletCurrency).containsExactly("USD", "XOF");
    }

    @Test
    void biggestWalletByValueFirst_aLargeDollarWalletCoversAloneTheRestOfTheCfaCommission() {
        // Colis XOF 1050 : portefeuille XOF 300, reste 750 XOF. EUR 0,40 (≈ 262 F CFA) et USD 5,00
        // (≈ 3 036 F CFA) : le dollar est le plus gros, il couvre seul 750 F CFA = 1,23 $.
        wallets("XOF", "300", "EUR", "0.40", "USD", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));

        assertThat(s.covered()).isTrue();
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("XOF", "USD");
        assertThat(s.lines().get(1).debitAmount()).isEqualByComparingTo("1.23");
        assertThat(s.lines().get(1).coveredInBid()).isEqualByComparingTo("750");
    }

    @Test
    void fourWalletsNeeded_eachPartialOneDrainedExactly_lastOneConvertedToTheRest() {
        // Colis XOF 3 700 : XOF 100 → reste 3 600. Du plus gros au plus petit : USD 5,00 (≈ 3 036 F CFA)
        // vidé, reste 564 ; EUR 0,60 (≈ 393 F CFA) vidé, reste 171 ; GBP 0,50 (≈ 381 F CFA) règle le
        // reste, converti. Quatre portefeuilles, un seul prélèvement chacun.
        wallets("XOF", "100", "EUR", "0.60", "GBP", "0.50", "USD", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("3700"));

        assertThat(s.covered()).isTrue();
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency)
                .containsExactly("XOF", "USD", "EUR", "GBP");
        assertThat(s.lines().get(1).debitAmount()).isEqualByComparingTo("5.00");
        assertThat(s.lines().get(2).debitAmount()).isEqualByComparingTo("0.60");
        assertThat(s.lines().get(3).coveredInBid()).isEqualByComparingTo("171");
        BigDecimal covered = s.lines().stream().map(CommissionLine::coveredInBid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(covered).isEqualByComparingTo("3700");
        // Un seul prélèvement par portefeuille (INV-09).
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).doesNotHaveDuplicates();
    }

    @Test
    void threeWalletsNeeded_partialWalletsDrainedExactly() {
        // Colis XOF 3 400 : XOF 100 → reste 3 300. USD 4,00 (≈ 2 429 F CFA) est le plus gros mais ne
        // suffit pas : vidé. Reste 871. EUR 1,50 (≈ 983 F CFA) règle le reste, converti.
        wallets("XOF", "100", "USD", "4.00", "EUR", "1.50");

        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("3400"));

        assertThat(s.covered()).isTrue();
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("XOF", "USD", "EUR");
        assertThat(s.lines().get(1).debitAmount()).isEqualByComparingTo("4.00");
        assertThat(s.lines().get(1).coveredInBid()).isEqualByComparingTo("2429");
        BigDecimal covered = s.lines().stream().map(CommissionLine::coveredInBid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(covered).isEqualByComparingTo("3400");
        assertThat(s.lines().get(2).coveredInBid()).isEqualByComparingTo("871");
    }

    @Test
    void preferredCurrency_isTakenFirstAfterTheBidWallet() {
        // Le voyageur a choisi les euros comme complément : ils partent avant le dollar, plus gros.
        wallets("XOF", "100", "EUR", "0.50", "USD", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", "EUR", new BigDecimal("1050"));

        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("XOF", "EUR", "USD");
        assertThat(s.lines().get(1).debitAmount()).isEqualByComparingTo("0.50");
        assertThat(s.lines().get(2).debitAmount()).isEqualByComparingTo("1.03");
    }

    @Test
    void withoutPreferredCurrency_theBiggestWalletCoversAlone() {
        wallets("XOF", "100", "EUR", "0.50", "USD", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("XOF", "USD");
        assertThat(s.lines().get(1).debitAmount()).isEqualByComparingTo("1.56");
    }

    @Test
    void allWalletsTogetherInsufficient_nothingDebited_andTheShortfallIsReported() {
        // XOF 100 + EUR 0,10 (≈ 65 F CFA) = 165 F CFA pour 1 050 : manque 885.
        wallets("XOF", "100", "EUR", "0.10");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));

        assertThat(s.covered()).isFalse();
        assertThat(s.lines()).isEmpty();
        assertThat(s.availableInBid()).isEqualByComparingTo("165");
        assertThat(s.shortfallInBid()).isEqualByComparingTo("885");
        assertThat(s.wallets()).extracting(CommissionSplit.WalletShare::currency).containsExactly("XOF", "EUR");
        assertThatThrownBy(() -> collector.executeForBid(s, traveler, bid))
                .isInstanceOf(IllegalStateException.class);
        verify(walletService, never()).debit(any(), any(), any(), any(), any(UUID.class));
    }

    @Test
    void emptyBidWallet_everythingOnAnotherWallet() {
        wallets("EUR", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));

        assertThat(s.covered()).isTrue();
        assertThat(s.fromBidWallet()).isEqualByComparingTo("0");
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("EUR");
        assertThat(s.lines().get(0).debitAmount()).isEqualByComparingTo("1.60");
    }

    @Test
    void aDustWalletThatCoversNothing_isIgnored() {
        // 0,001 € vaut 0 F CFA une fois arrondi vers le bas : aucune ligne à zéro.
        wallets("XOF", "100", "EUR", "0.001", "USD", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("XOF", "USD");
    }

    @Test
    void zeroCommission_singleZeroDebitOnActive() {
        wallets("EUR", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", BigDecimal.ZERO);
        collector.executeForBid(s, traveler, bid);

        assertThat(s.covered()).isTrue();
        assertThat(s.lines()).isEmpty();
        verify(walletService).debit(traveler, "EUR", BigDecimal.ZERO,
                WalletTransactionType.COMMISSION_DEDUCTED, bid);
    }

    @Test
    void currencyCodesAreNormalized() {
        wallets("XOF", "600", "EUR", "5.00");

        CommissionSplit s = collector.plan(traveler, " xof ", "eur", new BigDecimal("1050"));

        assertThat(s.bidCurrency()).isEqualTo("XOF");
        assertThat(s.activeCurrency()).isEqualTo("EUR");
        assertThat(s.covered()).isTrue();
    }

    // ─── Taux ─────────────────────────────────────────────────────────────────

    @Test
    void staleRate_walletExcludedFromTheSpend() {
        wallets("XOF", "100", "USD", "5.00");
        when(freshness.isStale("USD")).thenReturn(true);

        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        assertThat(s.covered()).isFalse();
        assertThat(s.excluded()).containsEntry("USD", "rate-stale");
        assertThat(s.availableInBid()).isEqualByComparingTo("100");
        // L'admin est prévenu : des portefeuilles sont écartés tant que le taux n'est pas mis à jour.
        verify(freshness).alertStale("USD");
    }

    @Test
    void aFreshRate_raisesNoAlert() {
        wallets("XOF", "100", "USD", "5.00");

        collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        verify(freshness, never()).alertStale(anyString());
    }

    @Test
    void missingRate_walletExcluded_theOthersStillServe() {
        wallets("XOF", "100", "CAD", "9.00", "EUR", "5.00");
        when(lookup.unitsPerEur("CAD")).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "exchange-rate-missing", "Exchange Rate Missing", "pas de taux"));

        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        assertThat(s.covered()).isTrue();
        assertThat(s.excluded()).containsEntry("CAD", "rate-missing");
        assertThat(s.lines()).extracting(CommissionLine::walletCurrency).containsExactly("XOF", "EUR");
    }

    @Test
    void theStalenessIsOnlyTestedWhenTheBidWalletDoesNotCoverEverything() {
        wallets("XOF", "2000", "USD", "5.00");

        collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        verify(freshness, never()).isStale(anyString());
    }

    // ─── Exécution ────────────────────────────────────────────────────────────

    @Test
    void anyDebitThrowingInsufficient_propagatesUnchanged_noCatchInCollector() {
        wallets("XOF", "600", "EUR", "5.00");
        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        doThrow(new InsufficientWalletBalanceException(new BigDecimal("0.10"), new BigDecimal("0.69")))
                .when(walletService).debit(eq(traveler), eq("EUR"), any(), any(), any(UUID.class), any(), any(), any());

        assertThatThrownBy(() -> collector.executeForBid(s, traveler, bid))
                .isInstanceOf(InsufficientWalletBalanceException.class);
    }

    @Test
    void negotiationVariant_usesPrefixedIdempotencyKeys_activeKeepsItsHistoricSuffix() {
        wallets("XOF", "600", "EUR", "5.00");

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForNegotiation(s, traveler, "thread-1", "nego_commission_wallet_t1");

        verify(walletService).debit(traveler, "XOF", new BigDecimal("600"),
                WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "nego_commission_wallet_t1");
        verify(walletService).debit(eq(traveler), eq("EUR"), amount("0.69"),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq("thread-1"),
                eq("nego_commission_wallet_t1_active"), eq("XOF"), amount("450"), any());
    }

    @Test
    void negotiationVariant_anotherWalletCarriesItsCurrencyCodeInTheKey() {
        wallets("USD", "3.00", "XOF", "1500");

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("4.00"));
        collector.executeForNegotiation(s, traveler, "thread-2", "nego_commission_wallet_t2");

        verify(walletService).debit(eq(traveler), eq("USD"), amount("3.00"),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq("thread-2"),
                eq("nego_commission_wallet_t2_USD"), eq("EUR"), amount("2.77"), any());
        verify(walletService).debit(eq(traveler), eq("XOF"), amount("807"),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq("thread-2"),
                eq("nego_commission_wallet_t2_XOF"), eq("EUR"), amount("1.23"), any());
    }

    @Test
    void executeForSupplement_deuxPortefeuilles_typeDedieRefEtCleParPortefeuille() {
        wallets("XOF", "600", "EUR", "5.00");
        UUID supplement = UUID.randomUUID();

        CommissionSplit s = collector.plan(traveler, "XOF", "EUR", new BigDecimal("1050"));
        collector.executeForSupplement(s, traveler, bid, supplement);

        verify(walletService).debit(traveler, "XOF", new BigDecimal("600"),
                WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED, bid,
                "supplement-" + supplement, "supplement-commission-" + supplement, null, null, null);
        verify(walletService).debit(eq(traveler), eq("EUR"), amount("0.69"),
                eq(WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED), eq(bid),
                eq("supplement-" + supplement), eq("supplement-commission-" + supplement + "-active"), eq("XOF"),
                amount("450"), any());
        // Jamais le type de la commission principale (INV-09) ni ses surcharges.
        verify(walletService, never()).debit(any(), any(), any(),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), any(UUID.class));
    }

    @Test
    void executeForSupplement_troisDevises_uneCleParPortefeuille() {
        wallets("USD", "3.00", "XOF", "1500");
        UUID supplement = UUID.randomUUID();

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("4.00"));
        collector.executeForSupplement(s, traveler, bid, supplement);

        verify(walletService).debit(eq(traveler), eq("USD"), amount("3.00"),
                eq(WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED), eq(bid),
                eq("supplement-" + supplement), eq("supplement-commission-" + supplement + "-USD"), eq("EUR"),
                amount("2.77"), any());
        verify(walletService).debit(eq(traveler), eq("XOF"), amount("807"),
                eq(WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED), eq(bid),
                eq("supplement-" + supplement), eq("supplement-commission-" + supplement + "-XOF"), eq("EUR"),
                amount("1.23"), any());
    }

    @Test
    void executeForSupplement_memeDevise_uneSeuleLigne_etRienSiCommissionNulle() {
        wallets("EUR", "5.00");
        UUID supplement = UUID.randomUUID();

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("2.00"));
        collector.executeForSupplement(s, traveler, bid, supplement);
        verify(walletService).debit(traveler, "EUR", new BigDecimal("2.00"),
                WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED, bid,
                "supplement-" + supplement, "supplement-commission-" + supplement, null, null, null);

        CommissionSplit zero = collector.plan(traveler, "EUR", "EUR", BigDecimal.ZERO);
        collector.executeForSupplement(zero, traveler, bid, UUID.randomUUID());
        // Aucune ligne à zéro pour une commission nulle : un seul débit au total.
        verify(walletService, org.mockito.Mockito.times(1)).debit(any(), any(), any(),
                eq(WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED), any(UUID.class), any(), any(),
                any(), any(), any());
    }

    @Test
    void executeForSupplement_nonCouverte_aucunDebit() {
        wallets("XOF", "100");
        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        assertThatThrownBy(() -> collector.executeForSupplement(s, traveler, bid, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        verify(walletService, never()).debit(any(), any(), any(), any(), any(UUID.class), any(), any(),
                any(), any(), any());
    }

    @Test
    void executeForAmendment_troisDevises_typeDedieRefEtCleParPortefeuille() {
        wallets("USD", "3.00", "XOF", "1500");
        UUID amendment = UUID.randomUUID();

        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("4.00"));
        collector.executeForAmendment(s, traveler, bid, amendment);

        verify(walletService).debit(eq(traveler), eq("USD"), amount("3.00"),
                eq(WalletTransactionType.AMENDMENT_COMMISSION_DEDUCTED), eq(bid),
                eq("amendment-" + amendment), eq("amendment-commission-" + amendment + "-USD"), eq("EUR"),
                amount("2.77"), any());
        verify(walletService).debit(eq(traveler), eq("XOF"), amount("807"),
                eq(WalletTransactionType.AMENDMENT_COMMISSION_DEDUCTED), eq(bid),
                eq("amendment-" + amendment), eq("amendment-commission-" + amendment + "-XOF"), eq("EUR"),
                amount("1.23"), any());
    }

    @Test
    void executeForAmendment_nonCouverte_aucunDebit() {
        wallets("XOF", "100");
        CommissionSplit s = collector.plan(traveler, "XOF", "XOF", new BigDecimal("1050"));

        assertThatThrownBy(() -> collector.executeForAmendment(s, traveler, bid, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void auditLines_oneEntryPerDebitedWallet() {
        wallets("USD", "3.00", "XOF", "1500");
        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("4.00"));

        var audit = WalletCommissionCollector.auditLines(s);

        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)).containsEntry("currency", "USD").containsEntry("debit", "3.00")
                .containsEntry("coveredInBid", "2.77");
        assertThat(audit.get(1)).containsEntry("currency", "XOF").containsEntry("debit", "807");
    }

    // ─── Aperçu et taux du jour ───────────────────────────────────────────────

    @Test
    void preview_givesTheSameSplitAsPlan_withoutAnyLock() {
        when(walletService.getBalance(eq(traveler), anyString())).thenReturn(BigDecimal.ZERO);
        when(walletService.getBalance(traveler, "USD")).thenReturn(new BigDecimal("3.00"));
        when(walletService.getBalance(traveler, "XOF")).thenReturn(new BigDecimal("1500"));
        when(walletService.getAllBalances(traveler)).thenReturn(List.of(wallet("USD", "3.00"), wallet("XOF", "1500")));

        CommissionSplit preview = collector.preview(traveler, "EUR", "EUR", null, new BigDecimal("4.00"));

        assertThat(preview.covered()).isTrue();
        assertThat(preview.lines()).extracting(CommissionLine::walletCurrency).containsExactly("USD", "XOF");
        assertThat(preview.lines().get(0).coveredInBid()).isEqualByComparingTo("2.77");
        assertThat(preview.lines().get(1).debitAmount()).isEqualByComparingTo("807");
        // Lecture seule : aucun verrou, aucun débit.
        verify(walletService, never()).getBalanceForUpdate(any(), any());
        verify(walletService, never()).debit(any(), any(), any(), any(), any(UUID.class));
    }

    @Test
    void preview_aFrozenWalletIsIgnored_andReportedAsExcluded() {
        when(walletService.getBalance(eq(traveler), anyString())).thenReturn(BigDecimal.ZERO);
        when(walletService.getBalance(traveler, "EUR")).thenReturn(new BigDecimal("5.00"));
        when(walletService.getAllBalances(traveler)).thenReturn(List.of(wallet("EUR", "5.00"), wallet("USD", "9.00")));
        when(walletService.isFrozen(traveler, "USD")).thenReturn(true);

        CommissionSplit preview = collector.preview(traveler, "XOF", "EUR", null, new BigDecimal("1050"));

        assertThat(preview.excluded()).containsEntry("USD", "frozen");
        assertThat(preview.lines()).extracting(CommissionLine::walletCurrency).containsExactly("EUR");
    }

    @Test
    void rateOfTheDay_isTheTableRate_notTheRoundedAppliedRate() {
        assertThat(collector.rateOfTheDay("EUR", "XOF")).isEqualByComparingTo("655.957");
        assertThat(collector.rateOfTheDay("EUR", "USD")).isEqualByComparingTo("1.08");
        assertThat(collector.rateOfTheDay("XOF", "EUR")).isEqualByComparingTo("0.001524");
        assertThat(collector.rateOfTheDay("XOF", "USD")).isEqualByComparingTo("0.001646");
        assertThat(collector.rateOfTheDay("eur", "EUR")).isEqualByComparingTo("1");
    }

    @Test
    void auditCurrencies_listsTheDebitedCurrenciesAndNoAmount() {
        wallets("USD", "3.00", "XOF", "1500");
        CommissionSplit s = collector.plan(traveler, "EUR", "EUR", new BigDecimal("4.00"));

        List<String> audit = WalletCommissionCollector.auditCurrencies(s);

        assertThat(audit).containsExactly("USD", "XOF");
        assertThat(audit.toString()).doesNotContain("3.00").doesNotContain("807");
    }

    // ─── Alternatives (contrat historique) ───────────────────────────────────

    @Test
    void alternatives_listsOtherWalletsCoveringTheRemainder_atTodaysRate() {
        // Colis XOF 1050, portefeuille XOF 600 → reste 450 XOF. EUR (complément) exclu ; USD 5.00
        // couvre 0.75, GBP 0.10 ne couvre pas 0.59, CAD gelé, XAF vide.
        when(walletService.getBalance(traveler, "XOF")).thenReturn(new BigDecimal("600"));
        when(walletService.isFrozen(traveler, "CAD")).thenReturn(true);
        when(walletService.getAllBalances(traveler)).thenReturn(List.of(
                wallet("XOF", "600"), wallet("EUR", "0.10"), wallet("USD", "5.00"),
                wallet("GBP", "0.10"), wallet("CAD", "50.00"), wallet("XAF", "0")));

        var result = collector.alternatives(traveler, "XOF", "EUR", new BigDecimal("1050"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currency()).isEqualTo("USD");
        assertThat(result.get(0).balance()).isEqualByComparingTo("5.00");
        assertThat(result.get(0).required()).isEqualByComparingTo("0.74");
        verify(walletService, never()).getBalanceForUpdate(any(), any());
    }

    @Test
    void alternatives_aStaleRateWalletIsNeverSuggested() {
        when(walletService.getBalance(traveler, "XOF")).thenReturn(new BigDecimal("600"));
        when(walletService.getAllBalances(traveler)).thenReturn(List.of(wallet("USD", "5.00")));
        when(freshness.isStale("USD")).thenReturn(true);

        assertThat(collector.alternatives(traveler, "XOF", "EUR", new BigDecimal("1050"))).isEmpty();
    }

    @Test
    void alternatives_emptyWhenBidWalletAloneCovers() {
        when(walletService.getBalance(traveler, "EUR")).thenReturn(new BigDecimal("20.00"));

        assertThat(collector.alternatives(traveler, "EUR", "EUR", new BigDecimal("12.00"))).isEmpty();
    }

    @Test
    void alternatives_frozenBidWallet_countsAsZero() {
        when(walletService.isFrozen(traveler, "XOF")).thenReturn(true);
        when(walletService.getAllBalances(traveler)).thenReturn(List.of(wallet("USD", "5.00")));

        var result = collector.alternatives(traveler, "XOF", "EUR", new BigDecimal("1050"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currency()).isEqualTo("USD");
        verify(walletService, never()).getBalance(traveler, "XOF");
    }

    // ─── Utilitaires ──────────────────────────────────────────────────────────

    @Test
    void amendmentPaymentRef_prefixeDedie() {
        UUID id = UUID.randomUUID();
        assertThat(WalletCommissionCollector.amendmentPaymentRef(id)).isEqualTo("amendment-" + id);
    }

    @Test
    void normalize_majusculesSansEspaces_videSiAbsent() {
        assertThat(WalletCommissionCollector.normalize(" xof ")).isEqualTo("XOF");
        assertThat(WalletCommissionCollector.normalize(null)).isEmpty();
    }

    @Test
    void complementCurrency_devisesChoisieOuActive_422SiInconnue() {
        ActiveCurrencyResolver resolver = mock(ActiveCurrencyResolver.class);
        when(resolver.resolve(traveler)).thenReturn("xof");

        assertThat(WalletCommissionCollector.complementCurrency(resolver, traveler, null)).isEqualTo("XOF");
        assertThat(WalletCommissionCollector.complementCurrency(resolver, traveler, "  ")).isEqualTo("XOF");
        assertThat(WalletCommissionCollector.complementCurrency(resolver, traveler, "eur")).isEqualTo("EUR");
        assertThatThrownBy(() -> WalletCommissionCollector.complementCurrency(resolver, traveler, "ZZZ"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("funding-currency-invalid"));
    }
}
