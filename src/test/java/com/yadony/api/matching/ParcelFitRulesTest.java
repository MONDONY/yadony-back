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
}
