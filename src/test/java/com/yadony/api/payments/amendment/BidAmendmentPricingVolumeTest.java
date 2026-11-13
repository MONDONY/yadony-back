package com.yadony.api.payments.amendment;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.common.commission.IncrementalCommission;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;

/**
 * Prix d'un ajout de volume sur un trajet bateau vendu au m³ : net = m³ × prix au m³ publié (net voyageur), même
 * commission différentielle que l'ajout de kilos, payée par l'expéditeur.
 */
@ExtendWith(MockitoExtension.class)
class BidAmendmentPricingVolumeTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    @Mock CommissionGridService gridService;
    @Mock CommissionRateResolver rateResolver;
    @Mock BidSupplementRepository supplements;
    @Mock BidAmendmentRepository amendments;
    @Mock BidGridItemRepository bidGridItems;
    @Mock AnnouncementPriceGridItemRepository announcementGridItems;

    private BidAmendmentPricing pricing;

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
    }

    @Test
    void quote_volume_netIsVolumeTimesRate() {
        // Principal : 0,5 m³ × 80 € = 40 € (C(40) = 2,80). Ajout : 0,25 m³ × 80 € = 20 € (C(60) = 4,20) → 1,40 €.
        AnnouncementEntity trip = m3Trip("80.00", "EUR");
        BidEntity bid = m3Bid("0.500", "EUR");

        BidAmendmentQuote q = pricing.quote(bid, trip, null, new BigDecimal("0.250"), List.of());

        assertThat(q.net()).isEqualByComparingTo("20.00");
        assertThat(q.commission()).isEqualByComparingTo("1.40");
        assertThat(q.gross()).isEqualByComparingTo("21.40");
        assertThat(q.gridId()).isEqualTo(GRID_7_6_5.id());
        assertThat(q.lines()).isEmpty();
    }

    @Test
    void quote_volume_roundedOnceToMinorUnit() {
        // 0,125 m³ × 33,33 € = 4,16625 € → 4,17 € ; en F CFA, franc entier.
        assertThat(pricing.quote(m3Bid("0.500", "EUR"), m3Trip("33.33", "EUR"), null, new BigDecimal("0.125"),
                List.of()).net().toPlainString()).isEqualTo("4.17");
        assertThat(pricing.quote(m3Bid("0.500", "XOF"), m3Trip("52477", "XOF"), null, new BigDecimal("0.333"),
                List.of()).net().toPlainString()).isEqualTo("17475");
    }

    @Test
    void quote_volumeOnKgTrip_422() {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTransportMode(TransportMode.PLANE);
        trip.setPricePerKg(new BigDecimal("10.00"));
        BidEntity bid = m3Bid("0.500", "EUR");

        assertThatThrownBy(() -> pricing.quote(bid, trip, null, new BigDecimal("0.5"), List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("amendment-not-eligible");
                });
    }

    @Test
    void quote_volumeOnBoatKgTrip_422() {
        // Bateau au kilo (volume facultatif, pas de prix au m³) : le volume ne s'y vend pas.
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTransportMode(TransportMode.BOAT);
        trip.setPricePerKg(new BigDecimal("4.00"));
        trip.setAvailableVolumeM3(new BigDecimal("3.000"));

        assertThatThrownBy(() -> pricing.quote(m3Bid("0.500", "EUR"), trip, null, new BigDecimal("0.5"), List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-eligible"));
    }

    @Test
    void quote_kgOnM3Trip_422() {
        assertThatThrownBy(() -> pricing.quote(m3Bid("0.500", "EUR"), m3Trip("80.00", "EUR"), new BigDecimal("2"),
                null, List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-eligible"));
    }

    @Test
    void quote_kgOnly_unchanged() {
        // Même exemple que BidAmendmentPricingTest.quote_legacyBid_usesFrozenRate : 2 kg × 4,50 € = 9 €, 10 % figés.
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTransportMode(TransportMode.PLANE);
        trip.setPricePerKg(new BigDecimal("4.50"));
        BidEntity bid = new BidEntity();
        BidAmendmentTestSupport.setId(bid, UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setCurrency("EUR");
        bid.setCommissionRate(new BigDecimal("0.10"));

        BidAmendmentQuote withoutVolume = pricing.quote(bid, trip, new BigDecimal("2"), List.of());
        BidAmendmentQuote nullVolume = pricing.quote(bid, trip, new BigDecimal("2"), null, List.of());

        assertThat(withoutVolume.net()).isEqualByComparingTo("9.00");
        assertThat(withoutVolume.commission()).isEqualByComparingTo("0.90");
        assertThat(nullVolume).isEqualTo(withoutVolume);
    }

    @Test
    void quote_negativeVolume_400() {
        assertThatThrownBy(() -> pricing.quote(m3Bid("0.500", "EUR"), m3Trip("80.00", "EUR"), null,
                new BigDecimal("-0.1"), List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrorCode()).isEqualTo("validation");
                });
    }

    @Test
    void quote_zeroVolumeAndNothingElse_422Empty() {
        assertThatThrownBy(() -> pricing.quote(m3Bid("0.500", "EUR"), m3Trip("80.00", "EUR"), null,
                BigDecimal.ZERO, List.of()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-empty"));
    }

    private static AnnouncementEntity m3Trip(String pricePerM3, String currency) {
        AnnouncementEntity trip = new AnnouncementEntity();
        BidAmendmentTestSupport.setId(trip, UUID.randomUUID());
        trip.setTravelerId(UUID.randomUUID());
        trip.setTransportMode(TransportMode.BOAT);
        trip.setPricePerM3(new BigDecimal(pricePerM3));
        trip.setVolumeCapacityM3(new BigDecimal("5.000"));
        trip.setAvailableVolumeM3(new BigDecimal("4.000"));
        trip.setCurrency(currency);
        return trip;
    }

    private static BidEntity m3Bid(String volume, String currency) {
        BidEntity bid = new BidEntity();
        BidAmendmentTestSupport.setId(bid, UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setVolumeM3(new BigDecimal(volume));
        bid.setCurrency(currency);
        bid.setCommissionGridId(GRID_7_6_5.id());
        return bid;
    }
}
