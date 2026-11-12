package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParcelFitRulesTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static AnnouncementEntity trip(ItemSize maxSize, String maxKg) {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setMaxItemSize(maxSize);
        trip.setMaxParcelWeightKg(maxKg == null ? null : bd(maxKg));
        return trip;
    }

    @Test
    @DisplayName("taille max gardée en voiture et bus seulement")
    void maxItemSize_keptForRoadOnly() {
        assertThat(ParcelFitRules.normalizeMaxItemSize(ItemSize.L, TransportMode.CAR)).isEqualTo(ItemSize.L);
        assertThat(ParcelFitRules.normalizeMaxItemSize(ItemSize.L, TransportMode.BUS)).isEqualTo(ItemSize.L);
        assertThat(ParcelFitRules.normalizeMaxItemSize(ItemSize.L, TransportMode.PLANE)).isNull();
        assertThat(ParcelFitRules.normalizeMaxItemSize(ItemSize.L, TransportMode.BOAT)).isNull();
        assertThat(ParcelFitRules.normalizeMaxItemSize(ItemSize.L, TransportMode.TRAIN)).isNull();
        assertThat(ParcelFitRules.normalizeMaxItemSize(ItemSize.L, TransportMode.OTHER)).isNull();
        assertThat(ParcelFitRules.normalizeMaxItemSize(null, TransportMode.CAR)).isNull();
    }

    @Test
    @DisplayName("poids max gardé en voiture, bus et bateau seulement")
    void maxParcelWeight_keptForRoadAndBoat() {
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("20"), TransportMode.BOAT)).isEqualByComparingTo("20");
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("20"), TransportMode.CAR)).isEqualByComparingTo("20");
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("20"), TransportMode.BUS)).isEqualByComparingTo("20");
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("20"), TransportMode.PLANE)).isNull();
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("20"), TransportMode.TRAIN)).isNull();
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("20"), TransportMode.OTHER)).isNull();
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(null, TransportMode.CAR)).isNull();
    }

    @Test
    @DisplayName("le poids max est ramené à 2 décimales (NUMERIC(8,2))")
    void maxParcelWeight_roundedToTwoDecimals() {
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("12.345"), TransportMode.CAR))
                .isEqualTo(bd("12.35"));
    }

    @Test
    @DisplayName("poids max à 0 (sentinelle d'effacement) ou négatif : aucune limite")
    void maxParcelWeight_zeroOrNegativeMeansNoLimit() {
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(BigDecimal.ZERO, TransportMode.CAR)).isNull();
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("0.00"), TransportMode.BOAT)).isNull();
        assertThat(ParcelFitRules.normalizeMaxParcelWeight(bd("-1"), TransportMode.CAR)).isNull();
    }

    @Test
    @DisplayName("modification : taille absente = conservée, NONE = effacée, valeur = remplacée, hors route = effacée")
    void resolveMaxItemSizeOnUpdate_rules() {
        assertThat(ParcelFitRules.resolveMaxItemSizeOnUpdate(null, ItemSize.M, TransportMode.CAR)).isEqualTo(ItemSize.M);
        assertThat(ParcelFitRules.resolveMaxItemSizeOnUpdate("NONE", ItemSize.M, TransportMode.CAR)).isNull();
        assertThat(ParcelFitRules.resolveMaxItemSizeOnUpdate("XL", ItemSize.M, TransportMode.BUS)).isEqualTo(ItemSize.XL);
        assertThat(ParcelFitRules.resolveMaxItemSizeOnUpdate(null, ItemSize.M, TransportMode.PLANE)).isNull();
        assertThat(ParcelFitRules.resolveMaxItemSizeOnUpdate("L", ItemSize.M, TransportMode.BOAT)).isNull();
        assertThat(ParcelFitRules.resolveMaxItemSizeOnUpdate(null, null, TransportMode.CAR)).isNull();
    }

    @Test
    @DisplayName("modification : poids absent = conservé, 0 = effacé, valeur = remplacée, hors route et bateau = effacé")
    void resolveMaxParcelWeightOnUpdate_rules() {
        assertThat(ParcelFitRules.resolveMaxParcelWeightOnUpdate(null, bd("30"), TransportMode.CAR))
                .isEqualByComparingTo("30");
        assertThat(ParcelFitRules.resolveMaxParcelWeightOnUpdate(bd("0"), bd("30"), TransportMode.CAR)).isNull();
        assertThat(ParcelFitRules.resolveMaxParcelWeightOnUpdate(bd("12"), bd("30"), TransportMode.BOAT))
                .isEqualByComparingTo("12");
        assertThat(ParcelFitRules.resolveMaxParcelWeightOnUpdate(null, bd("30"), TransportMode.BOAT))
                .isEqualByComparingTo("30");
        assertThat(ParcelFitRules.resolveMaxParcelWeightOnUpdate(null, bd("30"), TransportMode.TRAIN)).isNull();
        assertThat(ParcelFitRules.resolveMaxParcelWeightOnUpdate(bd("12"), bd("30"), TransportMode.PLANE)).isNull();
    }

    @Test
    @DisplayName("modèle et série (taille texte, poids Double) : absent = conservé, NONE/0 = effacé, hors mode = effacé")
    void resolveStoredLimits_rules() {
        assertThat(ParcelFitRules.resolveStoredMaxItemSize(null, "L", TransportMode.CAR)).isEqualTo("L");
        assertThat(ParcelFitRules.resolveStoredMaxItemSize("NONE", "L", TransportMode.CAR)).isNull();
        assertThat(ParcelFitRules.resolveStoredMaxItemSize("s", "L", TransportMode.BUS)).isEqualTo("S");
        assertThat(ParcelFitRules.resolveStoredMaxItemSize(null, "L", TransportMode.BOAT)).isNull();
        assertThat(ParcelFitRules.resolveStoredMaxItemSize("M", null, TransportMode.PLANE)).isNull();
        assertThat(ParcelFitRules.resolveStoredMaxItemSize(null, "n'importe quoi", TransportMode.CAR)).isNull();

        assertThat(ParcelFitRules.resolveStoredMaxParcelWeight(null, 30.0, TransportMode.CAR)).isEqualTo(30.0);
        assertThat(ParcelFitRules.resolveStoredMaxParcelWeight(0.0, 30.0, TransportMode.CAR)).isNull();
        assertThat(ParcelFitRules.resolveStoredMaxParcelWeight(20.5, 30.0, TransportMode.BOAT)).isEqualTo(20.5);
        assertThat(ParcelFitRules.resolveStoredMaxParcelWeight(12.0, null, TransportMode.TRAIN)).isNull();
        assertThat(ParcelFitRules.resolveStoredMaxParcelWeight(null, null, TransportMode.CAR)).isNull();
    }

    @Test
    @DisplayName("unité de capacité par défaut : kilo exact en route, valise 23 kg sinon")
    void defaultCapacityUnit_perMode() {
        assertThat(ParcelFitRules.defaultCapacityUnit(TransportMode.CAR)).isEqualTo(CapacityUnit.KG_EXACT);
        assertThat(ParcelFitRules.defaultCapacityUnit(TransportMode.BUS)).isEqualTo(CapacityUnit.KG_EXACT);
        assertThat(ParcelFitRules.defaultCapacityUnit(TransportMode.PLANE)).isEqualTo(CapacityUnit.SUITCASE_23KG);
        assertThat(ParcelFitRules.defaultCapacityUnit(TransportMode.BOAT)).isEqualTo(CapacityUnit.SUITCASE_23KG);
        assertThat(ParcelFitRules.defaultCapacityUnit(TransportMode.TRAIN)).isEqualTo(CapacityUnit.SUITCASE_23KG);
        assertThat(ParcelFitRules.defaultCapacityUnit(TransportMode.OTHER)).isEqualTo(CapacityUnit.SUITCASE_23KG);
        assertThat(ParcelFitRules.defaultCapacityUnit(null)).isEqualTo(CapacityUnit.SUITCASE_23KG);
    }

    @Test
    void assertFits_tooHeavy() {
        AnnouncementEntity trip = trip(null, "20");

        assertThatThrownBy(() -> ParcelFitRules.assertFits(trip, bd("25"), null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("parcel-too-heavy");
                    assertThat(ex.getMessage()).contains("20 kg");
                });
    }

    @Test
    void assertFits_tooBig() {
        AnnouncementEntity trip = trip(ItemSize.M, null);

        assertThatThrownBy(() -> ParcelFitRules.assertFits(trip, bd("5"), ItemSize.L))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("parcel-too-big");
                    assertThat(ex.getMessage()).contains("M");
                });
    }

    @Test
    @DisplayName("poids trop lourd et taille trop grande : le poids est signalé d'abord")
    void assertFits_weightCheckedBeforeSize() {
        AnnouncementEntity trip = trip(ItemSize.S, "10");

        assertThatThrownBy(() -> ParcelFitRules.assertFits(trip, bd("11"), ItemSize.XL))
                .hasFieldOrPropertyWithValue("errorCode", "parcel-too-heavy");
    }

    @Test
    @DisplayName("côté inconnu : jamais de blocage (C25)")
    void assertFits_unknownSideNeverBlocks() {
        assertThatCode(() -> ParcelFitRules.assertFits(trip(ItemSize.M, null), bd("5"), null)).doesNotThrowAnyException();
        assertThatCode(() -> ParcelFitRules.assertFits(trip(null, null), bd("5"), ItemSize.XL)).doesNotThrowAnyException();
        assertThatCode(() -> ParcelFitRules.assertFits(trip(null, "20"), null, null)).doesNotThrowAnyException();
        assertThatCode(() -> ParcelFitRules.assertFits(trip(null, null), null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("limite exacte acceptée : poids égal au max, taille égale à la taille max")
    void assertFits_boundaryAccepted() {
        assertThatCode(() -> ParcelFitRules.assertFits(trip(ItemSize.L, "20"), bd("20"), ItemSize.L))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParcelFitRules.assertFits(trip(ItemSize.L, "20"), bd("19.99"), ItemSize.S))
                .doesNotThrowAnyException();
    }

    @Test
    void fits_mirrorsAssertFitsWithoutThrowing() {
        assertThat(ParcelFitRules.fits(trip(ItemSize.M, "20"), bd("20"), ItemSize.M)).isTrue();
        assertThat(ParcelFitRules.fits(trip(ItemSize.M, "20"), bd("20.01"), ItemSize.M)).isFalse();
        assertThat(ParcelFitRules.fits(trip(ItemSize.M, "20"), bd("5"), ItemSize.L)).isFalse();
        assertThat(ParcelFitRules.fits(trip(ItemSize.M, "20"), null, null)).isTrue();
        assertThat(ParcelFitRules.fits(trip(null, null), bd("500"), ItemSize.XL)).isTrue();
    }

    @Test
    @DisplayName("taille d'une demande gardée en voiture, bus et « peu importe » (null), effacée sinon")
    void requestItemSize_keptForRoadAndAny() {
        assertThat(ParcelFitRules.normalizeRequestItemSize(ItemSize.M, TransportMode.CAR)).isEqualTo(ItemSize.M);
        assertThat(ParcelFitRules.normalizeRequestItemSize(ItemSize.M, TransportMode.BUS)).isEqualTo(ItemSize.M);
        assertThat(ParcelFitRules.normalizeRequestItemSize(ItemSize.M, null)).isEqualTo(ItemSize.M);
        assertThat(ParcelFitRules.normalizeRequestItemSize(ItemSize.M, TransportMode.PLANE)).isNull();
        assertThat(ParcelFitRules.normalizeRequestItemSize(ItemSize.M, TransportMode.BOAT)).isNull();
        assertThat(ParcelFitRules.normalizeRequestItemSize(ItemSize.M, TransportMode.TRAIN)).isNull();
        assertThat(ParcelFitRules.normalizeRequestItemSize(null, TransportMode.CAR)).isNull();
    }

    @Test
    @DisplayName("taille d'une demande à la modification : absente = conservée, NONE = effacée, sinon remplacée")
    void requestItemSize_onUpdate() {
        assertThat(ParcelFitRules.resolveRequestItemSizeOnUpdate(null, ItemSize.L, TransportMode.CAR))
                .isEqualTo(ItemSize.L);
        assertThat(ParcelFitRules.resolveRequestItemSizeOnUpdate("NONE", ItemSize.L, TransportMode.CAR)).isNull();
        assertThat(ParcelFitRules.resolveRequestItemSizeOnUpdate("xl", ItemSize.L, TransportMode.BUS))
                .isEqualTo(ItemSize.XL);
        assertThat(ParcelFitRules.resolveRequestItemSizeOnUpdate(null, ItemSize.L, null)).isEqualTo(ItemSize.L);
        assertThat(ParcelFitRules.resolveRequestItemSizeOnUpdate(null, ItemSize.L, TransportMode.PLANE)).isNull();
        assertThat(ParcelFitRules.resolveRequestItemSizeOnUpdate("M", null, TransportMode.BOAT)).isNull();
    }

    // ─── Dimensions et volume d'une demande bateau (lot 3, BA4) ─────────────────────────

    @Test
    @DisplayName("volume : 60 x 40 x 40 cm = 0,096 m³")
    void volume_computedFromDimensions() {
        assertThat(ParcelFitRules.volumeM3(60, 40, 40)).isEqualByComparingTo("0.096");
        assertThat(ParcelFitRules.volumeM3(100, 100, 100)).isEqualByComparingTo("1.000");
        assertThat(ParcelFitRules.volumeM3(400, 400, 400)).isEqualByComparingTo("64.000");
    }

    @Test
    @DisplayName("volume : arrondi à 3 décimales HALF_UP, minimum 0,001")
    void volume_roundedHalfUp_withMinimum() {
        // 1 500 cm³ = 0,0015 m³ -> 0,002 (HALF_UP)
        assertThat(ParcelFitRules.volumeM3(10, 10, 15)).isEqualByComparingTo("0.002");
        // 1 499 cm³ = 0,001499 m³ -> 0,001
        assertThat(ParcelFitRules.volumeM3(1499, 1, 1)).isEqualByComparingTo("0.001");
        // 1 cm³ = 0,000001 m³ -> arrondi à 0, remonté au minimum 0,001
        assertThat(ParcelFitRules.volumeM3(1, 1, 1)).isEqualByComparingTo("0.001");
        assertThat(ParcelFitRules.volumeM3(1, 1, 1).scale()).isEqualTo(3);
    }

    @Test
    @DisplayName("volume : 36 926 037 cm³ = 36,926 m³ (pas de dépassement d'entier)")
    void volume_largeProduct_noOverflow() {
        assertThat(ParcelFitRules.volumeM3(333, 333, 333)).isEqualByComparingTo("36.926");
    }

    @Test
    @DisplayName("fitsVolume : le volume tient s'il est inférieur ou égal au restant")
    void fitsVolume_comparesVolumes() {
        assertThat(ParcelFitRules.fitsVolume(bd("0.500"), bd("0.096"))).isTrue();
        assertThat(ParcelFitRules.fitsVolume(bd("0.096"), bd("0.096"))).isTrue();
        assertThat(ParcelFitRules.fitsVolume(bd("0.050"), bd("0.096"))).isFalse();
    }

    @Test
    @DisplayName("fitsVolume : volume inconnu d'un côté ou de l'autre = aucun contrôle (spec 5.6)")
    void fitsVolume_unknownSide_notFiltered() {
        assertThat(ParcelFitRules.fitsVolume(null, bd("0.096"))).isTrue();
        assertThat(ParcelFitRules.fitsVolume(bd("0.050"), null)).isTrue();
        assertThat(ParcelFitRules.fitsVolume(null, null)).isTrue();
    }

    private static ParcelFitRules.RequestDimensions dims(int l, int w, int h) {
        return new ParcelFitRules.RequestDimensions(l, w, h, ParcelFitRules.volumeM3(l, w, h));
    }

    @Test
    @DisplayName("dimensions : trois valeurs en bateau et en « peu importe » donnent le volume")
    void dimensions_complete_keptForBoatAndAny() {
        for (TransportMode mode : new TransportMode[] {TransportMode.BOAT, null}) {
            var r = ParcelFitRules.resolveRequestDimensions(60, 40, 40, ParcelFitRules.RequestDimensions.NONE, mode, true);
            assertThat(r.lengthCm()).isEqualTo(60);
            assertThat(r.widthCm()).isEqualTo(40);
            assertThat(r.heightCm()).isEqualTo(40);
            assertThat(r.volumeM3()).isEqualByComparingTo("0.096");
        }
    }

    @Test
    @DisplayName("dimensions : effacées sans contrôle pour tout autre mode, même partielles")
    void dimensions_ignoredForOtherModes() {
        for (TransportMode mode : new TransportMode[] {TransportMode.PLANE, TransportMode.CAR, TransportMode.BUS,
                TransportMode.TRAIN, TransportMode.OTHER}) {
            assertThat(ParcelFitRules.resolveRequestDimensions(60, 40, 40, dims(10, 10, 10), mode, true))
                .isEqualTo(ParcelFitRules.RequestDimensions.NONE);
            assertThat(ParcelFitRules.resolveRequestDimensions(60, null, null, dims(10, 10, 10), mode, true))
                .isEqualTo(ParcelFitRules.RequestDimensions.NONE);
        }
    }

    @Test
    @DisplayName("dimensions : une ou deux seulement = 422 dimensions-incomplete, bateau comme « peu importe »")
    void dimensions_partial_422() {
        for (TransportMode mode : new TransportMode[] {TransportMode.BOAT, null}) {
            for (Integer[] partial : new Integer[][] {{60, null, null}, {null, 40, null}, {null, null, 40},
                    {60, 40, null}, {60, null, 40}, {null, 40, 40}}) {
                assertThatThrownBy(() -> ParcelFitRules.resolveRequestDimensions(
                        partial[0], partial[1], partial[2], ParcelFitRules.RequestDimensions.NONE, mode, true))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("dimensions-incomplete");
                    });
            }
        }
    }

    @Test
    @DisplayName("dimensions : aucune en bateau = 422 boat-dimensions-required, sauf brouillon ; « peu importe » les omet")
    void dimensions_absent_boatRequired() {
        assertThatThrownBy(() -> ParcelFitRules.resolveRequestDimensions(null, null, null,
                ParcelFitRules.RequestDimensions.NONE, TransportMode.BOAT, true))
            .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ex.getErrorCode()).isEqualTo("boat-dimensions-required");
            });
        assertThat(ParcelFitRules.resolveRequestDimensions(null, null, null,
                ParcelFitRules.RequestDimensions.NONE, TransportMode.BOAT, false))
            .isEqualTo(ParcelFitRules.RequestDimensions.NONE);
        assertThat(ParcelFitRules.resolveRequestDimensions(null, null, null,
                ParcelFitRules.RequestDimensions.NONE, null, true))
            .isEqualTo(ParcelFitRules.RequestDimensions.NONE);
    }

    @Test
    @DisplayName("dimensions : absentes à la modification = dimensions actuelles conservées")
    void dimensions_absent_keepsCurrent() {
        var current = dims(60, 40, 40);
        for (TransportMode mode : new TransportMode[] {TransportMode.BOAT, null}) {
            assertThat(ParcelFitRules.resolveRequestDimensions(null, null, null, current, mode, true))
                .isEqualTo(current);
        }
        var replaced = ParcelFitRules.resolveRequestDimensions(100, 100, 100, current, TransportMode.BOAT, true);
        assertThat(replaced.volumeM3()).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("assertBoatDimensionsPresent : bateau sans volume refusé, autres modes jamais")
    void assertBoatDimensionsPresent_onlyForBoat() {
        assertThatThrownBy(() -> ParcelFitRules.assertBoatDimensionsPresent(TransportMode.BOAT, null))
            .isInstanceOfSatisfying(YadonyBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("boat-dimensions-required"));
        assertThatCode(() -> ParcelFitRules.assertBoatDimensionsPresent(TransportMode.BOAT, bd("0.096")))
            .doesNotThrowAnyException();
        assertThatCode(() -> ParcelFitRules.assertBoatDimensionsPresent(TransportMode.PLANE, null))
            .doesNotThrowAnyException();
        assertThatCode(() -> ParcelFitRules.assertBoatDimensionsPresent(null, null)).doesNotThrowAnyException();
    }
}
