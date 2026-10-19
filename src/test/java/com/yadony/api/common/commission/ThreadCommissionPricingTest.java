package com.yadony.api.common.commission;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.promo.PromoDiscount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Commission d'un fil à grille figée : montant annoncé par {@code NegotiationService} et prélevé
 * par le rail espèces, vecteurs de la spec §4.6 (grille 7/6/5, minimums par défaut).
 */
@ExtendWith(MockitoExtension.class)
class ThreadCommissionPricingTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    @Mock private CommissionGridService gridService;
    @Mock private CommissionRateResolver rateResolver;

    private ThreadCommissionPricing pricing;

    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(gridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
        // Règle de repli du code promo : celle du résolveur (CommissionRateResolverTest), exécutée
        // pour de vrai sur le mock ; les bouchons de reductions(...) restent la source des réductions.
        lenient().when(rateResolver.reductionsWithPromoFallback(any(), any(), any(), any())).thenCallRealMethod();
        pricing = new ThreadCommissionPricing(gridService, rateResolver);
    }

    private void reductions(CommissionReductions r) {
        when(rateResolver.reductions(travelerId, senderId, null, null, threadId)).thenReturn(r);
    }

    private CommissionResult price(String net, String currency) {
        return pricing.price(GRID_7_6_5.id(), new BigDecimal(net), currency, travelerId, senderId, threadId);
    }

    @Test
    @DisplayName("devis d'affichage : mêmes montants, repli du code par la variante DEBUG du résolveur")
    void displayPrice_sameAmounts_usesDisplayFallback() {
        when(rateResolver.displayReductionsWithPromoFallback(travelerId, senderId, "WELCOME5", threadId))
                .thenReturn(new CommissionReductions(null, null, new BigDecimal("0.5")));
        when(rateResolver.reductionsWithPromoFallback(travelerId, senderId, "WELCOME5", threadId))
                .thenReturn(new CommissionReductions(null, null, new BigDecimal("0.5")));

        CommissionResult shown = pricing.displayPrice(GRID_7_6_5.id(), new BigDecimal("100"), "EUR",
                travelerId, senderId, threadId, "WELCOME5");
        CommissionResult charged = pricing.price(GRID_7_6_5.id(), new BigDecimal("100"), "EUR",
                travelerId, senderId, threadId, "WELCOME5");

        assertThat(shown).isEqualTo(charged);
        assertThat(shown.commission()).isEqualByComparingTo("3.50");
        verify(rateResolver).displayReductionsWithPromoFallback(travelerId, senderId, "WELCOME5", threadId);
    }

    @Test
    void price_eurTier1_isGridCommission() {
        reductions(CommissionReductions.NONE);

        CommissionResult result = price("35", "EUR");

        assertThat(result.commission()).isEqualByComparingTo("2.45");
        assertThat(result.gross()).isEqualByComparingTo("37.45");
        assertThat(result.minimumApplied()).isFalse();
    }

    @Test
    void price_xofBelowMinimum_isWholeFrancsMinimum() {
        reductions(CommissionReductions.NONE);

        CommissionResult result = price("5000", "XOF");

        assertThat(result.commission()).isEqualByComparingTo("650");
        assertThat(result.commission().scale()).isZero();
        assertThat(result.gross()).isEqualByComparingTo("5650");
        assertThat(result.minimumApplied()).isTrue();
    }

    @Test
    void price_xofTier1_isWholeFrancs() {
        reductions(CommissionReductions.NONE);

        assertThat(price("20000", "xof").commission()).isEqualByComparingTo("1400");
    }

    @Test
    void price_threadReductions_overrideAndVoucherAfterMinimum() {
        // Bon ×0,5 du fil appliqué après le minimum : 1,00 € devient 0,50 € (vecteur §4.6).
        reductions(new CommissionReductions(null, null, new BigDecimal("0.5")));

        assertThat(price("5", "EUR").commission()).isEqualByComparingTo("0.50");
        verify(rateResolver).reductions(travelerId, senderId, null, null, threadId);
    }

    @Test
    void price_threadOverride_isTheLowerRate() {
        reductions(new CommissionReductions(new BigDecimal("0.04"), null, null));

        assertThat(price("100", "EUR").commission()).isEqualByComparingTo("4.00");
    }

    @Test
    void price_unknownCurrency_defaultsToEur() {
        reductions(CommissionReductions.NONE);

        assertThat(price("20", null).commission()).isEqualByComparingTo("1.40");
    }

    @Test
    void price_zeroNet_costsNothingWithoutReadingTheGrid() {
        CommissionResult result = price("0.004", "EUR");

        assertThat(result.commission()).isEqualByComparingTo("0");
        assertThat(result.commission().scale()).isEqualTo(2);
        assertThat(result.gross()).isEqualByComparingTo(result.net());
        assertThat(result.effectiveRate()).isEqualByComparingTo("0");
        assertThat(result.minimumApplied()).isFalse();
        assertThat(result.promoEffective()).isFalse();
        verifyNoInteractions(gridService, rateResolver);
    }

    // ── Code promo du fil (rail mobile money, comme la carte) ──────────────

    private CommissionResult priceWithPromo(String net, String promoCode) {
        return pricing.price(GRID_7_6_5.id(), new BigDecimal(net), "XOF", travelerId, senderId, threadId, promoCode);
    }

    @Test
    void price_withValidPromo_isAppliedToEffectiveRate() {
        when(rateResolver.reductions(travelerId, senderId, "WELCOME05", senderId, threadId))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        CommissionResult result = priceWithPromo("100000", "WELCOME05");

        // Grille 6 656 FCFA (6,656 %) − 5 points = 1,656 % → 1 656.
        assertThat(result.commission()).isEqualByComparingTo("1656");
        assertThat(result.promoEffective()).isTrue();
    }

    @Test
    void price_withPromoCancelledByMinimum_isNotEffective() {
        when(rateResolver.reductions(travelerId, senderId, "WELCOME05", senderId, threadId))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        CommissionResult result = priceWithPromo("5000", "WELCOME05");

        assertThat(result.commission()).isEqualByComparingTo("650");
        assertThat(result.promoEffective()).isFalse();
    }

    @Test
    void price_withInvalidPromo_fallsBackWithoutCode() {
        when(rateResolver.reductions(travelerId, senderId, "OLD", senderId, threadId))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "promo-expired", "x", "x"));
        reductions(CommissionReductions.NONE);

        CommissionResult result = priceWithPromo("20000", "OLD");

        assertThat(result.commission()).isEqualByComparingTo("1400");
        assertThat(result.promoEffective()).isFalse();
    }

    @Test
    void price_withBlankPromo_readsReductionsWithoutCode() {
        reductions(CommissionReductions.NONE);

        assertThat(priceWithPromo("20000", "  ").commission()).isEqualByComparingTo("1400");
    }

    @Test
    void minimum_isTheFrozenGridMinimumOfTheThreadCurrency() {
        assertThat(pricing.minimum(GRID_7_6_5.id(), "XOF")).isEqualByComparingTo("650");
        assertThat(pricing.minimum(GRID_7_6_5.id(), null)).isEqualByComparingTo("1.00");
    }
}
