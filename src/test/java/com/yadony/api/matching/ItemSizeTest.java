package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemSizeTest {

    @Test
    void fitsWithin_isOrdered() {
        assertThat(ItemSize.M.fitsWithin(ItemSize.L)).isTrue();
        assertThat(ItemSize.L.fitsWithin(ItemSize.L)).isTrue();
        assertThat(ItemSize.S.fitsWithin(ItemSize.S)).isTrue();
        assertThat(ItemSize.XL.fitsWithin(ItemSize.L)).isFalse();
        assertThat(ItemSize.L.fitsWithin(ItemSize.M)).isFalse();
        assertThat(ItemSize.XL.fitsWithin(ItemSize.XL)).isTrue();
    }

    @Test
    void order_isSmallToExtraLarge() {
        assertThat(ItemSize.values()).containsExactly(ItemSize.S, ItemSize.M, ItemSize.L, ItemSize.XL);
    }

    @Test
    void parseOrNull_tolerant() {
        assertThat(ItemSize.parseOrNull(" xl ")).isEqualTo(ItemSize.XL);
        assertThat(ItemSize.parseOrNull("m")).isEqualTo(ItemSize.M);
        assertThat(ItemSize.parseOrNull("S")).isEqualTo(ItemSize.S);
        assertThat(ItemSize.parseOrNull("l\t")).isEqualTo(ItemSize.L);
    }

    @Test
    void parseOrNull_unknownBlankOrNull_returnsNull() {
        assertThat(ItemSize.parseOrNull("XXL")).isNull();
        assertThat(ItemSize.parseOrNull("")).isNull();
        assertThat(ItemSize.parseOrNull("   ")).isNull();
        assertThat(ItemSize.parseOrNull(null)).isNull();
        assertThat(ItemSize.parseOrNull("SMALL")).isNull();
    }
}
