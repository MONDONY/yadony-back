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

        PriceBreakdown p = pricing.price(b, a);

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

        PriceBreakdown p = pricing.price(b, a);

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

        PriceBreakdown p = pricing.price(b, a);

        assertThat(p.net()).isEqualByComparingTo("12000");
        assertThat(p.gross()).isEqualByComparingTo("13440");
        assertThat(p.commission()).isEqualByComparingTo("1440");
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

        assertThat(pricing.price(b, a).commission()).isEqualByComparingTo("360");
    }
}
