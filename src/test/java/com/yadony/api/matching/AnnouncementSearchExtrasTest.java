package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Clé de cache et constructeurs de compatibilité d'{@link AnnouncementSearchExtras}. */
class AnnouncementSearchExtrasTest {

    @Test
    void cacheKey_differsBySizeAndVia() {
        AnnouncementSearchExtras none = AnnouncementSearchExtras.NONE;
        AnnouncementSearchExtras sizeL = new AnnouncementSearchExtras(null, Set.of(), false, false, ItemSize.L, null);
        AnnouncementSearchExtras sizeXL = new AnnouncementSearchExtras(null, Set.of(), false, false, ItemSize.XL, null);
        AnnouncementSearchExtras viaTanger = new AnnouncementSearchExtras(null, Set.of(), false, false, null, "tanger");
        AnnouncementSearchExtras viaCasa = new AnnouncementSearchExtras(null, Set.of(), false, false, null, "casablanca");

        assertThat(java.util.List.of(none.cacheKey(), sizeL.cacheKey(), sizeXL.cacheKey(),
                viaTanger.cacheKey(), viaCasa.cacheKey())).doesNotHaveDuplicates();
        assertThat(sizeL.cacheKey()).contains("_size=L_via=null");
        assertThat(viaTanger.cacheKey()).contains("_size=null_via=tanger");
        assertThat(none.cacheKey()).contains("_size=null_via=null");
    }

    @Test
    void cacheKey_viaCityIsCaseAndSpaceInsensitive() {
        AnnouncementSearchExtras a = new AnnouncementSearchExtras(null, Set.of(), false, false, null, "  Tanger ");
        AnnouncementSearchExtras b = new AnnouncementSearchExtras(null, Set.of(), false, false, null, "TANGER");

        assertThat(a.viaCity()).isEqualTo("tanger");
        assertThat(a.cacheKey()).isEqualTo(b.cacheKey());
    }

    @Test
    void blankViaCity_isIgnored() {
        AnnouncementSearchExtras blank = new AnnouncementSearchExtras(null, Set.of(), false, false, null, "   ");

        assertThat(blank.viaCity()).isNull();
        assertThat(blank.cacheKey()).isEqualTo(AnnouncementSearchExtras.NONE.cacheKey());
    }

    @Test
    void compatibilityConstructors_leaveSizeAndViaEmpty() {
        assertThat(new AnnouncementSearchExtras(1, Set.of(PaymentMethod.CASH), true, true).minItemSize()).isNull();
        assertThat(new AnnouncementSearchExtras(1, Set.of(PaymentMethod.CASH), true, true).viaCity()).isNull();
        assertThat(new AnnouncementSearchExtras(1, Set.of(PaymentMethod.CASH), true).viaCity()).isNull();
        assertThat(new AnnouncementSearchExtras(1, null).minItemSize()).isNull();
        assertThat(AnnouncementSearchExtras.PUBLIC_VIEW.viaCity()).isNull();
    }

    @Test
    void unknownMinItemSize_ignored() {
        assertThat(ItemSize.parseOrNull("XXL")).isNull();
        assertThat(ItemSize.parseOrNull("")).isNull();
        assertThat(ItemSize.parseOrNull(" l ")).isEqualTo(ItemSize.L);
    }

    private static AnnouncementSearchExtras boat(java.math.BigDecimal minVolume, java.math.BigDecimal maxPrice,
                                                  java.time.LocalDate arrivalBefore) {
        return new AnnouncementSearchExtras(null, Set.of(), false, false, null, null, minVolume, maxPrice, arrivalBefore);
    }

    @Test
    void cacheKey_differsByEachBoatParameter() {
        var none = AnnouncementSearchExtras.NONE.cacheKey();
        var volume1 = boat(new java.math.BigDecimal("1"), null, null).cacheKey();
        var volume2 = boat(new java.math.BigDecimal("2"), null, null).cacheKey();
        var price600 = boat(null, new java.math.BigDecimal("600"), null).cacheKey();
        var price700 = boat(null, new java.math.BigDecimal("700"), null).cacheKey();
        var arrival1 = boat(null, null, java.time.LocalDate.of(2027, 1, 10)).cacheKey();
        var arrival2 = boat(null, null, java.time.LocalDate.of(2027, 1, 11)).cacheKey();

        assertThat(java.util.List.of(none, volume1, volume2, price600, price700, arrival1, arrival2))
                .doesNotHaveDuplicates();
        // Un même nombre à zéros de queue près partage la clé.
        assertThat(boat(new java.math.BigDecimal("1.50"), null, null).cacheKey())
                .isEqualTo(boat(new java.math.BigDecimal("1.5"), null, null).cacheKey());
    }

    @Test
    void sixArgumentConstructor_leavesBoatFiltersEmpty() {
        var extras = new AnnouncementSearchExtras(null, Set.of(), false, true, ItemSize.M, "tanger");

        assertThat(extras.minVolumeM3()).isNull();
        assertThat(extras.maxPricePerM3()).isNull();
        assertThat(extras.arrivalBefore()).isNull();
        assertThat(AnnouncementSearchExtras.NONE.cacheKey()).contains("_vol=null_m3=null_arr=null");
    }
}
