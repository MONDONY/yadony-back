package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.payments.PriceBreakdown;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MobileMoneyBidPricingTest {

    @Mock BidGridItemRepository gridItems;
    @Mock CommissionRateResolver rates;
    @InjectMocks MobileMoneyBidPricing pricing;

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
}
