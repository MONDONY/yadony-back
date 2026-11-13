package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prix et volume d'une réservation sur un trajet bateau vendu au m³ : volume calculé par le serveur depuis les
 * dimensions ({@link ParcelFitRules#resolveBidVolume}), net = volume × prix au m³ arrondi à l'unité mineure
 * ({@link BidNetTotal#volumeNet}), même part au barème pour l'affichage et chaque rail
 * ({@link BidNetTotal#scheduleNet}), borne technique et prédicat de mise en relation.
 */
@DisplayName("Bateau au m³ : prix et volume de la réservation")
class BoatCheckoutPricingTest {

    private static AnnouncementEntity boat(String currency, String pricePerM3, String availableVolumeM3) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTransportMode(TransportMode.BOAT);
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setCurrency(currency);
        a.setAvailableKg(null);
        a.setTotalKg(null);
        a.setPricePerKg(null);
        a.setPricePerM3(new BigDecimal(pricePerM3));
        a.setVolumeCapacityM3(new BigDecimal("2.5"));
        a.setAvailableVolumeM3(new BigDecimal(availableVolumeM3));
        return a;
    }

    private static AnnouncementEntity plane() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTransportMode(TransportMode.PLANE);
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal("20"));
        a.setPricePerKg(new BigDecimal("8.50"));
        return a;
    }

    private static BidEntity bid(String weightKg, String volumeM3) {
        BidEntity b = new BidEntity();
        b.setWeightKg(weightKg == null ? null : new BigDecimal(weightKg));
        b.setVolumeM3(volumeM3 == null ? null : new BigDecimal(volumeM3));
        return b;
    }

    @Nested
    @DisplayName("prix")
    class Price {

        @Test
        @DisplayName("checkout_boatPerM3_priceIsVolumeTimesRate : 0,096 m³ × 600 € = 57,60 €")
        void checkout_boatPerM3_priceIsVolumeTimesRate() {
            AnnouncementEntity trip = boat("EUR", "600", "2.5");
            BigDecimal volume = ParcelFitRules.resolveBidVolume(trip, 40, 40, 60);

            assertThat(volume).isEqualByComparingTo("0.096");
            assertThat(BidNetTotal.volumeNet(trip, volume)).isEqualByComparingTo("57.60");
            assertThat(BidNetTotal.bookableVolumeNet(trip, volume)).isEqualByComparingTo("57.60");
        }

        @Test
        @DisplayName("checkout_boatXof_roundsToMinorUnit : franc CFA entier, HALF_UP")
        void checkout_boatXof_roundsToMinorUnit() {
            // 0,096 × 123 457 = 11 851,872 → 11 852 F CFA.
            assertThat(BidNetTotal.volumeNet(boat("XOF", "123457", "2.5"), new BigDecimal("0.096")))
                    .isEqualByComparingTo("11852").hasScaleOf(0);
            // 0,125 × 1 004 = 125,5 → 126 (demi vers le haut).
            assertThat(BidNetTotal.volumeNet(boat("XOF", "1004", "2.5"), new BigDecimal("0.125")))
                    .isEqualByComparingTo("126");
            // Euro : 0,333 × 1,5 = 0,4995 → 0,50.
            assertThat(BidNetTotal.volumeNet(boat("EUR", "1.5", "2.5"), new BigDecimal("0.333")))
                    .isEqualByComparingTo("0.50");
        }

        @Test
        @DisplayName("part au barème : volume au m³ (le poids d'un colis au m³ n'est jamais tarifé), poids × prix/kg sinon")
        void scheduleNet_volumeOrWeight() {
            assertThat(BidNetTotal.scheduleNet(bid("12", "0.096"), boat("EUR", "600", "2.5")))
                    .isEqualByComparingTo("57.60");
            assertThat(BidNetTotal.scheduleNet(bid("12", null), boat("EUR", "600", "2.5")))
                    .isEqualByComparingTo("0");
            // Avion : inchangé, poids × prix au kilo sans arrondi.
            assertThat(BidNetTotal.scheduleNet(bid("2.5", "0.5"), plane())).isEqualByComparingTo("21.25");
            assertThat(BidNetTotal.scheduleNet(bid(null, null), plane())).isEqualByComparingTo("0");
            assertThat(BidNetTotal.scheduleNet(bid("2.5", null), null)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("net total affiché : volume au m³ ; accord négocié tel quel (le prix du fil, jamais volume × prix)")
        void compute_volumeOrNegotiated() {
            assertThat(BidNetTotal.compute(bid(null, "0.096"), boat("EUR", "600", "2.5"), List.of()))
                    .isEqualByComparingTo("57.60");
            BidEntity negotiated = bid(null, "0.096");
            negotiated.setNegotiatedNetEur(new BigDecimal("45"));
            assertThat(BidNetTotal.compute(negotiated, boat("EUR", "600", "2.5"), List.of()))
                    .isEqualByComparingTo("45.00");
        }

        @Test
        @DisplayName("borne technique NUMERIC(10,2) : au-delà, 422 volume-price-too-high à la réservation ; l'affichage ne lève jamais")
        void bookableVolumeNet_technicalBound() {
            AnnouncementEntity trip = boat("XOF", "99999999.99", "64");

            assertThatThrownBy(() -> BidNetTotal.bookableVolumeNet(trip, new BigDecimal("2")))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("volume-price-too-high");
                    });
            assertThatCode(() -> BidNetTotal.volumeNet(trip, new BigDecimal("2"))).doesNotThrowAnyException();
            // 1 × 99 999 999,99 XOF arrondi au franc = 100 000 000 : au-delà de la borne aussi.
            assertThatThrownBy(() -> BidNetTotal.bookableVolumeNet(trip, BigDecimal.ONE))
                    .isInstanceOf(YadonyBusinessException.class);
            // En euro, 1 m³ au prix maximal tient exactement.
            assertThat(BidNetTotal.bookableVolumeNet(boat("EUR", "99999999.99", "64"), BigDecimal.ONE))
                    .isEqualByComparingTo("99999999.99");
        }

        @Test
        @DisplayName("borne technique : exactement 99 999 999,99 accepté, un centime de plus refusé")
        void assertWithinTechnicalBound_edges() {
            assertThatCode(() -> BidNetTotal.assertWithinTechnicalBound(new BigDecimal("99999999.99")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> BidNetTotal.assertWithinTechnicalBound(null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> BidNetTotal.assertWithinTechnicalBound(new BigDecimal("100000000.00")))
                    .isInstanceOf(YadonyBusinessException.class);
        }
    }

    @Nested
    @DisplayName("volume")
    class Volume {

        @Test
        @DisplayName("checkout_boatWithoutDimensions_422 : trajet au m³ sans dimensions → 422 boat-dimensions-required")
        void checkout_boatWithoutDimensions_422() {
            assertThatThrownBy(() -> ParcelFitRules.resolveBidVolume(boat("EUR", "600", "2.5"), null, null, null))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("boat-dimensions-required");
                    });
        }

        @Test
        @DisplayName("une ou deux dimensions seulement → 422 dimensions-incomplete")
        void partialDimensions_422() {
            assertThatThrownBy(() -> ParcelFitRules.resolveBidVolume(boat("EUR", "600", "2.5"), 40, null, 60))
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo("dimensions-incomplete"));
            assertThatThrownBy(() -> ParcelFitRules.resolveBidVolume(boat("EUR", "600", "2.5"), 40, null, null))
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo("dimensions-incomplete"));
        }

        @Test
        @DisplayName("hors trajet au m³ (avion, bateau au kilo) : dimensions ignorées, aucun volume")
        void notPerM3_dimensionsIgnored() {
            assertThat(ParcelFitRules.resolveBidVolume(plane(), 40, 40, 60)).isNull();
            AnnouncementEntity boatPerKg = plane();
            boatPerKg.setTransportMode(TransportMode.BOAT);
            boatPerKg.setVolumeCapacityM3(new BigDecimal("2"));
            assertThat(ParcelFitRules.resolveBidVolume(boatPerKg, 40, 40, 60)).isNull();
            assertThat(ParcelFitRules.resolveBidVolume(null, 40, 40, 60)).isNull();
        }

        @Test
        @DisplayName("volume minimal 1 litre (1 × 1 × 1 cm arrondi à 0,001 m³)")
        void minimalVolume() {
            assertThat(ParcelFitRules.resolveBidVolume(boat("EUR", "600", "2.5"), 1, 1, 1))
                    .isEqualByComparingTo("0.001");
        }

        @Test
        @DisplayName("checkout_boatVolumeTooLarge_422 : au-delà du volume restant → 422 insufficient-volume ; égal → accepté")
        void checkout_boatVolumeTooLarge_422() {
            AnnouncementEntity trip = boat("EUR", "600", "0.096");

            assertThatCode(() -> ParcelFitRules.assertVolumeFits(trip, new BigDecimal("0.096")))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> ParcelFitRules.assertVolumeFits(trip, new BigDecimal("0.097")))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("insufficient-volume");
                    });
            // Hors trajet au m³ : rien à comparer.
            assertThatCode(() -> ParcelFitRules.assertVolumeFits(plane(), new BigDecimal("99")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("mise en relation (matching, rematch) : volume connu et tenant dans le restant ; sans effet au kilo")
        void fitsTripVolume() {
            AnnouncementEntity trip = boat("EUR", "600", "0.5");

            assertThat(ParcelFitRules.fitsTripVolume(trip, new BigDecimal("0.5"))).isTrue();
            assertThat(ParcelFitRules.fitsTripVolume(trip, new BigDecimal("0.501"))).isFalse();
            assertThat(ParcelFitRules.fitsTripVolume(trip, null)).isFalse();
            assertThat(ParcelFitRules.fitsTripVolume(plane(), null)).isTrue();
            assertThat(ParcelFitRules.fitsTripVolume(plane(), new BigDecimal("99"))).isTrue();
            assertThat(ParcelFitRules.fitsTripVolume(null, null)).isTrue();
        }
    }
}
