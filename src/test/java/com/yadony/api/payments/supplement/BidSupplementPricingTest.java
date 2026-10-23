package com.yadony.api.payments.supplement;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.common.commission.IncrementalCommission;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.payments.amendment.BidExtrasNet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Prix d'un supplément (plan §1.3-b) : l'expéditeur ne paie que la DIFFÉRENCE de commission,
 * {@code C(N + S) − C(N)} sur la grille figée du bid, jamais le minimum de la devise deux fois ;
 * bid hérité sans grille : {@code S × taux}.
 */
@ExtendWith(MockitoExtension.class)
class BidSupplementPricingTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    private static final UUID TRAVELER = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();

    @Mock CommissionGridService gridService;
    @Mock CommissionRateResolver rateResolver;
    @Mock BidGridItemRepository gridItems;
    @Mock AnnouncementRepository announcements;
    @Mock BidExtrasNet extrasNet;

    private BidSupplementPricing pricing;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        pricing = new BidSupplementPricing(
                new IncrementalCommission(gridService, rateResolver, announcements), extrasNet, gridItems);
        lenient().when(extrasNet.paidExtrasNet(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(gridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
        lenient().when(gridItems.findByBidId(any())).thenReturn(List.of());
        announcement = new AnnouncementEntity();
        announcement.setTravelerId(TRAVELER);
        announcement.setPricePerKg(new BigDecimal("5.00"));
    }

    @Test
    void grille_differenceDeCommission_sansMinimumEnDouble() {
        // N = 2 kg × 5 € = 10 € : C(10) = minimum 1,00 € ; C(30) = 2,10 € ; supplément 20 € → 1,10 €.
        BidEntity bid = gridBid("2.00", "EUR");
        when(rateResolver.reductions(eq(TRAVELER), eq(SENDER), isNull(), isNull(), eq(bid.getId())))
                .thenReturn(CommissionReductions.NONE);

        BidSupplementQuote q = pricing.quote(bid, announcement, new BigDecimal("20"));

        assertThat(q.net()).isEqualByComparingTo("20.00");
        assertThat(q.commission()).isEqualByComparingTo("1.10");
        assertThat(q.gross()).isEqualByComparingTo("21.10");
        assertThat(q.gridId()).isEqualTo(GRID_7_6_5.id());
        assertThat(q.rateSnapshot()).isEqualByComparingTo("0.055000");
    }

    @Test
    void grille_trancheMarginale_auDelaDe100Euros() {
        // N = 18 kg × 5 € = 90 € : C(90) = 6,30 ; C(110) = 7,00 + 0,60 = 7,60 → 1,30 €.
        BidEntity bid = gridBid("18.00", "EUR");
        when(rateResolver.reductions(any(), any(), isNull(), isNull(), any())).thenReturn(CommissionReductions.NONE);

        assertThat(pricing.quote(bid, announcement, new BigDecimal("20.00")).commission()).isEqualByComparingTo("1.30");
    }

    @Test
    void tauxSpecialAZero_aucuneCommission() {
        BidEntity bid = gridBid("2.00", "EUR");
        when(rateResolver.reductions(any(), any(), isNull(), isNull(), any()))
                .thenReturn(new CommissionReductions(BigDecimal.ZERO, null, null));

        BidSupplementQuote q = pricing.quote(bid, announcement, new BigDecimal("20"));

        assertThat(q.commission()).isEqualByComparingTo("0.00");
        assertThat(q.gross()).isEqualByComparingTo("20.00");
    }

    @Test
    void bonDeParrainage_appliqueAuxDeuxBornes() {
        // C(10) = 1,00 × 0,5 = 0,50 ; C(30) = 2,10 × 0,5 = 1,05 → 0,55 €.
        BidEntity bid = gridBid("2.00", "EUR");
        when(rateResolver.reductions(any(), any(), isNull(), isNull(), any()))
                .thenReturn(new CommissionReductions(null, null, new BigDecimal("0.5")));

        assertThat(pricing.quote(bid, announcement, new BigDecimal("20")).commission()).isEqualByComparingTo("0.55");
    }

    @Test
    void bidHerite_supplementFoisTaux_sansMinimum() {
        BidEntity bid = bid("2.00", "EUR");
        bid.setCommissionRate(new BigDecimal("0.12"));

        BidSupplementQuote q = pricing.quote(bid, announcement, new BigDecimal("3.33"));

        assertThat(q.commission()).isEqualByComparingTo("0.40");
        assertThat(q.gross()).isEqualByComparingTo("3.73");
        assertThat(q.gridId()).isNull();
        verify(gridService, never()).require(any());
    }

    @Test
    void bidHeriteSansTauxFige_tauxResoluPourLeCouple() {
        BidEntity bid = bid("2.00", "EUR");
        when(rateResolver.resolve(TRAVELER, SENDER)).thenReturn(new BigDecimal("0.10"));

        assertThat(pricing.quote(bid, announcement, new BigDecimal("20")).commission()).isEqualByComparingTo("2.00");
    }

    @Test
    void xof_francsEntiers() {
        // N = 2 kg × 3 000 = 6 000 F : C(6 000) = minimum 650 ; C(19 000) = 1 330 → 680 F.
        announcement.setPricePerKg(new BigDecimal("3000"));
        BidEntity bid = gridBid("2.00", "XOF");
        when(rateResolver.reductions(any(), any(), isNull(), isNull(), any())).thenReturn(CommissionReductions.NONE);

        BidSupplementQuote q = pricing.quote(bid, announcement, new BigDecimal("13000.40"));

        assertThat(q.net().toPlainString()).isEqualTo("13000");
        assertThat(q.commission().toPlainString()).isEqualTo("680");
        assertThat(q.gross().toPlainString()).isEqualTo("13680");
    }

    @Test
    void negocie_netFigeEtReductionsFigeesDuBid_sansRelireLeResolveur() {
        BidEntity bid = gridBid("2.00", "EUR");
        bid.setNegotiatedNetEur(new BigDecimal("50.00"));
        bid.setNegotiatedGrossEur(new BigDecimal("53.50"));
        bid.setCommissionOverrideRate(new BigDecimal("0.03"));

        // C(50) = min(3,50 ; 1,50) = 1,50 ; C(60) = 1,80 → 0,30 €.
        assertThat(pricing.quote(bid, announcement, new BigDecimal("10")).commission()).isEqualByComparingTo("0.30");
        verify(rateResolver, never()).reductions(any(), any(), any(), any(), any());
    }

    @Test
    void supplementDejaPaye_entreDansLeNetDejaDu() {
        // N = 10 € + 20 € déjà payés = 30 € : C(30) = 2,10 ; C(50) = 3,50 → 1,40 € (et non 1,10 €).
        BidEntity bid = gridBid("2.00", "EUR");
        when(rateResolver.reductions(any(), any(), isNull(), isNull(), any())).thenReturn(CommissionReductions.NONE);
        when(extrasNet.paidExtrasNet(bid.getId())).thenReturn(new BigDecimal("20.00"));

        assertThat(pricing.quote(bid, announcement, new BigDecimal("20")).commission()).isEqualByComparingTo("1.40");
    }

    @Test
    void netNulOuNegatif_refuse() {
        BidEntity bid = gridBid("2.00", "EUR");
        assertThatThrownBy(() -> pricing.quote(bid, announcement, new BigDecimal("0.004")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BidEntity gridBid(String weight, String currency) {
        BidEntity bid = bid(weight, currency);
        bid.setCommissionGridId(GRID_7_6_5.id());
        return bid;
    }

    private static BidEntity bid(String weight, String currency) {
        BidEntity bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setSenderId(SENDER);
        bid.setWeightKg(new BigDecimal(weight));
        bid.setCurrency(currency);
        return bid;
    }

    static void setId(Object entity, UUID id) {
        try {
            Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
