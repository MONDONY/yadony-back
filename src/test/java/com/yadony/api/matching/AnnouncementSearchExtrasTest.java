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
        assertThat(sizeL.cacheKey()).endsWith("_size=L_via=null");
        assertThat(viaTanger.cacheKey()).endsWith("_size=null_via=tanger");
        assertThat(none.cacheKey()).endsWith("_size=null_via=null");
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
}
