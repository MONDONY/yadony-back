package com.yadony.api.assistant.parcel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingNumbersTest {

    @Test
    void reconnaitLesEcrituresCourantes() {
        assertThat(TrackingNumbers.find("mon colis DON-7K3M9QXA svp")).contains("DON-7K3M9QXA");
        assertThat(TrackingNumbers.find("don-7k3m9qxa")).contains("DON-7K3M9QXA");
        assertThat(TrackingNumbers.find("DON 7K3M9QXA")).contains("DON-7K3M9QXA");
        assertThat(TrackingNumbers.find("DON7K3M9QXA")).contains("DON-7K3M9QXA");
        assertThat(TrackingNumbers.find("(DON - 7K3M9QXA)")).contains("DON-7K3M9QXA");
    }

    @Test
    void ignoreCeQuiNEnEstPas() {
        assertThat(TrackingNumbers.find("donne-moi 12345678")).isEmpty();
        assertThat(TrackingNumbers.find("DON-7K3M")).isEmpty();
        assertThat(TrackingNumbers.find("DON-7K3M9QXAB")).isEmpty();
        assertThat(TrackingNumbers.find("pardon 7K3M9QXA")).isEmpty();
        assertThat(TrackingNumbers.find(null)).isEmpty();
    }

    @Test
    void masqueEtRetireTousLesNumeros() {
        assertThat(TrackingNumbers.mask("DON-7K3M9QXA et don 2222AAAA"))
                .isEqualTo("[tracking number] et [tracking number]");
        assertThat(TrackingNumbers.strip("DON-7K3M9QXA remboursé ?")).isEqualTo("remboursé ?");
        assertThat(TrackingNumbers.strip("DON-7K3M9QXA")).isEmpty();
        assertThat(TrackingNumbers.mask(null)).isNull();
        assertThat(TrackingNumbers.strip(null)).isEmpty();
    }
}
