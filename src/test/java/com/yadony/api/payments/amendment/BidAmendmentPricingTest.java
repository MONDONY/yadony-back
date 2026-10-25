package com.yadony.api.payments.amendment;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.common.commission.IncrementalCommission;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.payments.amendment.dto.AmendmentLineRequest;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prix d'une modification : net = kilos × prix publié du trajet + Σ articles de CE trajet, commission
 * différentielle sur le net déjà dû (principal + ajouts payés).
 */
@ExtendWith(MockitoExtension.class)
class BidAmendmentPricingTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    private static final UUID TRAVELER = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID PHONE_ID = UUID.randomUUID();

    @Mock CommissionGridService gridService;
    @Mock CommissionRateResolver rateResolver;
    @Mock BidSupplementRepository supplements;
    @Mock BidAmendmentRepository amendments;
    @Mock BidGridItemRepository bidGridItems;
    @Mock AnnouncementPriceGridItemRepository announcementGridItems;

    private BidAmendmentPricing pricing;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        pricing = new BidAmendmentPricing(
                new IncrementalCommission(gridService, rateResolver),
                new BidExtrasNet(supplements, amendments, bidGridItems), announcementGridItems);
        lenient().when(supplements.sumNetByBidIdAndStatusIn(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(amendments.sumNetByBidIdAndStatusIn(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(gridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
        lenient().when(bidGridItems.findByBidId(any())).thenReturn(List.of());
        lenient().when(rateResolver.reductions(any(), any(), isNull(), isNull(), any()))
                .thenReturn(CommissionReductions.NONE);

        announcement = new AnnouncementEntity();
        BidAmendmentTestSupport.setId(announcement, ANNOUNCEMENT_ID);
        announcement.setTravelerId(TRAVELER);
        announcement.setPricePerKg(new BigDecimal("4.50"));
        lenient().when(announcementGridItems.findByAnnouncementIdOrderByPositionAsc(ANNOUNCEMENT_ID))
                .thenReturn(List.of(gridItem(PHONE_ID, "Téléphone", "10.00")));
    }

    @Test
    void quote_kgAndItem_example() {
        // N = 2 kg × 4,50 = 9 € ; S = 3 × 4,50 + 10 = 23,50 €.
        // C(9) = minimum 1,00 ; C(32,50) = 2,275 → 2,28 ; commission 1,28 €.
        BidEntity bid = gridBid("2.00", "EUR");

        BidAmendmentQuote q = pricing.quote(bid, announcement, new BigDecimal("3"),
                List.of(new AmendmentLineRequest(PHONE_ID, 1)));

        assertThat(q.net()).isEqualByComparingTo("23.50");
        assertThat(q.commission()).isEqualByComparingTo("1.28");
        assertThat(q.gross()).isEqualByComparingTo(q.net().add(q.commission()));
        assertThat(q.gridId()).isEqualTo(GRID_7_6_5.id());
        assertThat(q.lines()).containsExactly(new BidAmendmentQuote.Line(PHONE_ID, "Téléphone", new BigDecimal("10.00"), 1));
    }

    @Test
    void quote_quantityMultipliesUnitPrice() {
        BidEntity bid = gridBid("2.00", "EUR");

        BidAmendmentQuote q = pricing.quote(bid, announcement, null, List.of(new AmendmentLineRequest(PHONE_ID, 3)));

        assertThat(q.net()).isEqualByComparingTo("30.00");
    }

    @Test
    void quote_negotiatedBid_usesPublishedPricePerKg() {
        // Net négocié 50 € figé, mais les kilos ajoutés se paient au prix publié du trajet : 2 × 4,50 = 9 €.
        BidEntity bid = gridBid("2.00", "EUR");
        bid.setNegotiatedNetEur(new BigDecimal("50.00"));
        bid.setCommissionOverrideRate(new BigDecimal("0.03"));

        BidAmendmentQuote q = pricing.quote(bid, announcement, new BigDecimal("2"), List.of());

        assertThat(q.net()).isEqualByComparingTo("9.00");
        // C(50) = min(3,50 ; 1,50) = 1,50 ; C(59) = 1,77 → 0,27 €.
        assertThat(q.commission()).isEqualByComparingTo("0.27");
        verify(bidGridItems, never()).findByBidId(any());
        verify(rateResolver, never()).reductions(any(), any(), any(), any(), any());
    }

    @Test
    void quote_foreignGridItem_422() {
        BidEntity bid = gridBid("2.00", "EUR");
        UUID foreign = UUID.randomUUID();

        assertThatThrownBy(() -> pricing.quote(bid, announcement, new BigDecimal("1"),
                List.of(new AmendmentLineRequest(foreign, 1))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("amendment-item-unknown");
                });
    }

    @Test
    void quote_duplicateGridItem_422() {
        // Un même article en deux lignes contournerait le plafond de quantité par ligne.
        BidEntity bid = gridBid("2.00", "EUR");

        assertThatThrownBy(() -> pricing.quote(bid, announcement, null,
                List.of(new AmendmentLineRequest(PHONE_ID, 1), new AmendmentLineRequest(PHONE_ID, 2))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("amendment-item-duplicate");
                });
    }

    @Test
    void quote_quantityBelowOne_rejectedEvenWithoutDtoValidation() {
        BidEntity bid = gridBid("2.00", "EUR");

        // Une quantité négative ferait baisser le net des autres lignes.
        assertThatThrownBy(() -> pricing.quote(bid, announcement, new BigDecimal("3"),
                List.of(new AmendmentLineRequest(PHONE_ID, -1))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrorCode()).isEqualTo("validation");
                });
        assertThatThrownBy(() -> pricing.quote(bid, announcement, new BigDecimal("3"),
                List.of(new AmendmentLineRequest(PHONE_ID, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("validation"));
    }

    @Test
    void quote_negativeKg_rejected() {
        BidEntity bid = gridBid("2.00", "EUR");

        assertThatThrownBy(() -> pricing.quote(bid, announcement, new BigDecimal("-1"),
                List.of(new AmendmentLineRequest(PHONE_ID, 3))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("validation"));
    }

    @Test
    void quote_nothingAdded_422Empty() {
        BidEntity bid = gridBid("2.00", "EUR");

        assertThatThrownBy(() -> pricing.quote(bid, announcement, BigDecimal.ZERO, List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("amendment-empty");
                });
        assertThatThrownBy(() -> pricing.quote(bid, announcement, null, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-empty"));
    }

    @Test
    void quote_netRoundingToZero_422Empty() {
        BidEntity bid = gridBid("2.00", "EUR");

        assertThatThrownBy(() -> pricing.quote(bid, announcement, new BigDecimal("0.001"), List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-empty"));
    }

    @Test
    void quote_kgWithoutPricePerKg_422() {
        announcement.setPricePerKg(null);
        BidEntity bid = gridBid("2.00", "EUR");

        assertThatThrownBy(() -> pricing.quote(bid, announcement, new BigDecimal("1"), List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("amendment-not-eligible");
                });
    }

    @Test
    void quote_includesPaidSupplementInBase() {
        // N = 2 kg × 5 € = 10 € + 20 € déjà payés (supplément ou modification) = 30 € : C(30) = 2,10 ;
        // S = 20 € : C(50) = 3,50 → 1,40 € (et non 1,10 € sur la seule base du principal).
        BidEntity bid = gridBid("2.00", "EUR");
        when(supplements.sumNetByBidIdAndStatusIn(eq(bid.getId()), any())).thenReturn(new BigDecimal("20.00"));
        announcement.setPricePerKg(new BigDecimal("5.00"));

        BidAmendmentQuote q = pricing.quote(bid, announcement, new BigDecimal("4"), List.of());

        assertThat(q.net()).isEqualByComparingTo("20.00");
        assertThat(q.commission()).isEqualByComparingTo("1.40");
    }

    @Test
    void quote_legacyBid_usesFrozenRate() {
        BidEntity bid = bid("2.00", "EUR");
        bid.setCommissionRate(new BigDecimal("0.10"));

        BidAmendmentQuote q = pricing.quote(bid, announcement, new BigDecimal("2"), List.of());

        assertThat(q.net()).isEqualByComparingTo("9.00");
        assertThat(q.commission()).isEqualByComparingTo("0.90");
        assertThat(q.gridId()).isNull();
    }

    @Test
    void quote_xof_wholeFrancs() {
        announcement.setPricePerKg(new BigDecimal("3000"));
        BidEntity bid = gridBid("2.00", "XOF");

        BidAmendmentQuote q = pricing.quote(bid, announcement, new BigDecimal("1.5"), List.of());

        assertThat(q.net().toPlainString()).isEqualTo("4500");
        assertThat(q.gross().toPlainString()).isEqualTo(q.net().add(q.commission()).toPlainString());
    }

    private static AnnouncementPriceGridItemEntity gridItem(UUID id, String label, String unitNet) {
        AnnouncementPriceGridItemEntity item = new AnnouncementPriceGridItemEntity();
        item.setAnnouncementId(ANNOUNCEMENT_ID);
        item.setLabel(label);
        item.setUnitPriceNet(new BigDecimal(unitNet));
        try {
            Field f = AnnouncementPriceGridItemEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(item, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return item;
    }

    private static BidEntity gridBid(String weight, String currency) {
        BidEntity bid = bid(weight, currency);
        bid.setCommissionGridId(GRID_7_6_5.id());
        return bid;
    }

    private static BidEntity bid(String weight, String currency) {
        BidEntity bid = new BidEntity();
        BidAmendmentTestSupport.setId(bid, UUID.randomUUID());
        bid.setSenderId(SENDER);
        bid.setWeightKg(new BigDecimal(weight));
        bid.setCurrency(currency);
        return bid;
    }
}
