package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.payments.PriceBreakdown;
import com.yadony.api.promo.PromoDiscount;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MobileMoneyBidPricingTest {

    @Mock BidGridItemRepository gridItems;
    @Mock CommissionRateResolver rates;
    @Mock CommissionGridService gridService;
    @InjectMocks MobileMoneyBidPricing pricing;

    /** Grille 7/6/5 de la spec §4.6, minimums par défaut (650 FCFA). */
    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    private static AnnouncementEntity xof(BigDecimal pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setTravelerId(UUID.randomUUID());
        a.setCurrency("XOF");
        a.setPricePerKg(pricePerKg);
        return a;
    }

    private static BidEntity bid(BigDecimal weight) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setSenderId(UUID.randomUUID());
        b.setWeightKg(weight);
        return b;
    }

    @Test
    void kgBid_inXof_isRoundedToWholeUnits() {
        AnnouncementEntity a = xof(new BigDecimal("3000"));
        BidEntity b = bid(new BigDecimal("5"));
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.resolve(eq(a.getTravelerId()), eq(b.getSenderId()), isNull(), isNull(), eq(b.getId())))
                .thenReturn(new BigDecimal("0.12"));

        PriceBreakdown p = pricing.price(b, a).price();

        assertThat(p.net()).isEqualByComparingTo("15000");
        assertThat(p.commission()).isEqualByComparingTo("1800");
        assertThat(p.gross()).isEqualByComparingTo("16800");
        assertThat(p.net().scale()).isZero();
        assertThat(b.getCommissionRate()).isEqualByComparingTo("0.12");
    }

    @Test
    void gridItems_areAdded_andCommissionRoundsHalfUpToUnit() {
        AnnouncementEntity a = xof(null);
        BidEntity b = bid(null);
        BidGridItemEntity item = new BidGridItemEntity();
        item.setUnitPriceNetSnapshot(new BigDecimal("2505"));
        item.setQuantity(1);
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of(item));
        when(rates.resolve(any(), any(), isNull(), isNull(), any())).thenReturn(new BigDecimal("0.12"));

        PriceBreakdown p = pricing.price(b, a).price();

        assertThat(p.net()).isEqualByComparingTo("2505");
        assertThat(p.commission()).as("300.6 → 301").isEqualByComparingTo("301");
        assertThat(p.gross()).isEqualByComparingTo("2806");
    }

    @Test
    void negotiatedBid_usesFrozenGrossAndNet() {
        AnnouncementEntity a = xof(new BigDecimal("3000"));
        BidEntity b = bid(new BigDecimal("5"));
        b.setNegotiatedNetEur(new BigDecimal("12000"));
        b.setNegotiatedGrossEur(new BigDecimal("13440"));

        PriceBreakdown p = pricing.price(b, a).price();

        assertThat(p.net()).isEqualByComparingTo("12000");
        assertThat(p.gross()).isEqualByComparingTo("13440");
        assertThat(p.commission()).isEqualByComparingTo("1440");
    }

    /**
     * Le drapeau {@code promoApplied} est calculé par la MÊME résolution que le taux — jamais
     * par une seconde sonde à l'acceptation : vrai seulement si le promo a réellement été pris.
     */
    @Test
    void validPromo_isReportedAsApplied() {
        AnnouncementEntity a = xof(new BigDecimal("3000"));
        BidEntity b = bid(new BigDecimal("1"));
        b.setPromoCode("WELCOME10");
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.resolve(eq(a.getTravelerId()), eq(b.getSenderId()), eq("WELCOME10"), eq(b.getSenderId()), eq(b.getId())))
                .thenReturn(new BigDecimal("0.02"));

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        assertThat(quote.promoApplied()).isTrue();
        assertThat(quote.price().commission()).isEqualByComparingTo("60");
        assertThat(b.getCommissionRate()).isEqualByComparingTo("0.02");
    }

    @Test
    void noPromo_isNotReportedAsApplied() {
        AnnouncementEntity a = xof(new BigDecimal("3000"));
        BidEntity b = bid(new BigDecimal("1"));
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.resolve(any(), any(), isNull(), isNull(), any())).thenReturn(new BigDecimal("0.12"));

        assertThat(pricing.price(b, a).promoApplied()).isFalse();
    }

    @Test
    void invalidPromo_fallsBackSilently() {
        AnnouncementEntity a = xof(new BigDecimal("3000"));
        BidEntity b = bid(new BigDecimal("1"));
        b.setPromoCode("OLD");
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.resolve(eq(a.getTravelerId()), eq(b.getSenderId()), eq("OLD"), eq(b.getSenderId()), eq(b.getId())))
                .thenThrow(new com.yadony.api.common.YadonyBusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "promo-invalid", "x", "x"));
        when(rates.resolve(eq(a.getTravelerId()), eq(b.getSenderId()), isNull(), isNull(), eq(b.getId())))
                .thenReturn(new BigDecimal("0.12"));

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        assertThat(quote.price().commission()).isEqualByComparingTo("360");
        assertThat(quote.promoApplied()).as("replié : rien à racheter à l'acceptation").isFalse();
    }

    /**
     * Ronde 1, point 8 : le taux PERSISTÉ sur le bid et celui UTILISÉ pour la commission
     * doivent être identiques (comme le rail espèces, qui n'arrondit jamais ce taux avant de
     * s'en servir). Avec un arrondi intermédiaire à 4 décimales (comportement précédent), le
     * taux 0,123456 devenait 0,1235 pour le calcul : 15000 × 0,1235 = 1852,5 → 1853 — alors
     * que le taux réellement persisté (0,123456) donne 15000 × 0,123456 = 1851,84 → 1852.
     */
    @Test
    void rateWithMoreThanFourDecimals_commissionMatchesThePersistedRate() {
        AnnouncementEntity a = xof(new BigDecimal("3000"));
        BidEntity b = bid(new BigDecimal("5"));
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.resolve(eq(a.getTravelerId()), eq(b.getSenderId()), isNull(), isNull(), eq(b.getId())))
                .thenReturn(new BigDecimal("0.123456"));

        PriceBreakdown p = pricing.price(b, a).price();

        assertThat(b.getCommissionRate()).isEqualByComparingTo("0.123456");
        assertThat(p.commission()).as("1851.84 arrondi, pas 1852.5 arrondi").isEqualByComparingTo("1852");
    }

    // ── Bid à grille figée (spec §4.2, §4.4) ─────────────────────────────

    private BidEntity gridBid(BigDecimal weight) {
        BidEntity b = bid(weight);
        b.setCommissionGridId(GRID_7_6_5.id());
        when(gridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
        return b;
    }

    @Test
    void price_grid_xof_net20000_commission1400() {
        AnnouncementEntity a = xof(new BigDecimal("4000"));
        BidEntity b = gridBid(new BigDecimal("5"));
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.reductions(a.getTravelerId(), b.getSenderId(), null, null, b.getId()))
                .thenReturn(CommissionReductions.NONE);

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        assertThat(quote.price().net()).isEqualByComparingTo("20000");
        assertThat(quote.price().commission()).isEqualByComparingTo("1400");
        assertThat(quote.price().gross()).isEqualByComparingTo("21400");
        assertThat(quote.price().commission().scale()).as("francs entiers").isZero();
        assertThat(b.getCommissionRate()).as("taux effectif figé sur le bid").isEqualByComparingTo("0.07");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
    }

    @Test
    void price_grid_xof_net5000_minimum650() {
        AnnouncementEntity a = xof(new BigDecimal("1000"));
        BidEntity b = gridBid(new BigDecimal("5"));
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.reductions(a.getTravelerId(), b.getSenderId(), null, null, b.getId()))
                .thenReturn(CommissionReductions.NONE);

        PriceBreakdown p = pricing.price(b, a).price();

        assertThat(p.net()).isEqualByComparingTo("5000");
        assertThat(p.commission()).as("minimum XOF, et non 7 % = 350").isEqualByComparingTo("650");
        assertThat(p.gross()).isEqualByComparingTo("5650");
        assertThat(b.getCommissionRate()).isEqualByComparingTo("0.13");
    }

    /** Minimum au-dessus du net : le taux écrit sur le bid est borné sous 1 (CHECK V118). */
    @Test
    void price_grid_minimumAboveNet_storesBoundedRate() {
        AnnouncementEntity a = xof(new BigDecimal("500"));
        BidEntity b = gridBid(new BigDecimal("1"));
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.reductions(a.getTravelerId(), b.getSenderId(), null, null, b.getId()))
                .thenReturn(CommissionReductions.NONE);

        PriceBreakdown p = pricing.price(b, a).price();

        assertThat(p.commission()).isEqualByComparingTo("650");
        assertThat(p.gross()).isEqualByComparingTo("1150");
        assertThat(b.getCommissionRate()).isEqualByComparingTo("0.999999");
    }

    /** Le minimum annule tout le gain du code : ni appliqué ni effectif, donc jamais racheté. */
    @Test
    void price_grid_promoCancelledByMinimum_isNotApplied() {
        AnnouncementEntity a = xof(new BigDecimal("1000"));
        BidEntity b = gridBid(new BigDecimal("5"));
        b.setPromoCode("WELCOME05");
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.reductions(a.getTravelerId(), b.getSenderId(), "WELCOME05", b.getSenderId(), b.getId()))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        assertThat(quote.price().commission()).isEqualByComparingTo("650");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
    }

    @Test
    void price_grid_effectivePromo_isAppliedAndEffective() {
        AnnouncementEntity a = xof(new BigDecimal("4000"));
        BidEntity b = gridBid(new BigDecimal("25"));
        b.setPromoCode("WELCOME05");
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.reductions(a.getTravelerId(), b.getSenderId(), "WELCOME05", b.getSenderId(), b.getId()))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        // 100 000 FCFA : grille 6 656 → 6,656 % − 5 points = 1,656 % → 1 656.
        assertThat(quote.price().commission()).isEqualByComparingTo("1656");
        assertThat(quote.promoApplied()).isTrue();
        assertThat(quote.promoEffective()).isTrue();
        assertThat(b.getCommissionRate()).isEqualByComparingTo("0.01656");
    }

    @Test
    void price_grid_invalidPromo_fallsBackWithoutCode() {
        AnnouncementEntity a = xof(new BigDecimal("4000"));
        BidEntity b = gridBid(new BigDecimal("5"));
        b.setPromoCode("OLD");
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.reductions(a.getTravelerId(), b.getSenderId(), "OLD", b.getSenderId(), b.getId()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "promo-invalid", "x", "x"));
        when(rates.reductions(a.getTravelerId(), b.getSenderId(), null, null, b.getId()))
                .thenReturn(CommissionReductions.NONE);

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        assertThat(quote.price().commission()).isEqualByComparingTo("1400");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
    }

    /** Net négocié seul : réductions figées sur le bid à l'ouverture du fil, taux figé non réécrit. */
    @Test
    void price_grid_negotiatedNetOnly_usesFrozenBidReductions() {
        AnnouncementEntity a = xof(new BigDecimal("4000"));
        BidEntity b = gridBid(new BigDecimal("5"));
        b.setNegotiatedNetEur(new BigDecimal("20000"));
        b.setCommissionRate(new BigDecimal("0.04"));
        b.setCommissionOverrideRate(new BigDecimal("0.04"));
        b.setCommissionVoucherFactor(new BigDecimal("0.5"));
        b.setPromoCode("WELCOME05");

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        // min(1 400, 20 000 × 4 % = 800) = 800, bon ×0,5 → 400.
        assertThat(quote.price().net()).isEqualByComparingTo("20000");
        assertThat(quote.price().commission()).isEqualByComparingTo("400");
        assertThat(quote.price().gross()).isEqualByComparingTo("20400");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
        assertThat(b.getCommissionRate()).isEqualByComparingTo("0.04");
        verifyNoInteractions(rates, gridItems);
    }

    /** Accord négocié brut + net : figé, même sur un bid à grille ; la grille n'est pas lue. */
    @Test
    void price_grid_negotiatedGrossAndNet_staysFrozen() {
        AnnouncementEntity a = xof(new BigDecimal("4000"));
        BidEntity b = bid(new BigDecimal("5"));
        b.setCommissionGridId(GRID_7_6_5.id());
        b.setNegotiatedNetEur(new BigDecimal("12000"));
        b.setNegotiatedGrossEur(new BigDecimal("13440"));

        PriceBreakdown p = pricing.price(b, a).price();

        assertThat(p.commission()).isEqualByComparingTo("1440");
        verifyNoInteractions(gridService, rates);
    }

    @Test
    void price_grid_zeroNet_is422() {
        AnnouncementEntity a = xof(null);
        BidEntity b = gridBid(null);
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> pricing.price(b, a))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "invalid-amount");
    }

    /** Sans grille : calcul hérité, aucun plancher (7 % de 5 000 = 350, pas 650). */
    @Test
    void price_noGrid_legacyNoFloor() {
        AnnouncementEntity a = xof(new BigDecimal("1000"));
        BidEntity b = bid(new BigDecimal("5"));
        when(gridItems.findByBidId(b.getId())).thenReturn(List.of());
        when(rates.resolve(eq(a.getTravelerId()), eq(b.getSenderId()), isNull(), isNull(), eq(b.getId())))
                .thenReturn(new BigDecimal("0.07"));

        MobileMoneyBidPricing.Quote quote = pricing.price(b, a);

        assertThat(quote.price().commission()).isEqualByComparingTo("350");
        assertThat(quote.price().gross()).isEqualByComparingTo("5350");
        verifyNoInteractions(gridService);
    }
}
