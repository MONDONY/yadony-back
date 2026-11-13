package com.yadony.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.Test;

class TransportModeLabelsTest {

    @Test
    void chaqueModeALeSienEtAucunNePrendLeSymboleDeLAvion() {
        assertThat(TransportModeLabels.label(TransportMode.PLANE)).isEqualTo("Avion");
        assertThat(TransportModeLabels.label(TransportMode.BOAT)).isEqualTo("Bateau");
        assertThat(TransportModeLabels.label(TransportMode.CAR)).isEqualTo("Voiture");
        assertThat(TransportModeLabels.label(TransportMode.BUS)).isEqualTo("Bus");
        assertThat(TransportModeLabels.label(TransportMode.TRAIN)).isEqualTo("Train");
        assertThat(TransportModeLabels.label(TransportMode.OTHER)).isEqualTo("Autre moyen");

        assertThat(TransportModeLabels.symbol(TransportMode.PLANE)).isEqualTo("✈");
        for (TransportMode mode : TransportMode.values()) {
            if (mode != TransportMode.PLANE) {
                assertThat(TransportModeLabels.symbol(mode)).as("%s", mode).isNotEqualTo("✈");
                assertThat(TransportModeLabels.label(mode)).as("%s", mode).isNotEqualTo("Avion");
            }
        }
    }

    @Test
    void modeInconnuOuAutre_pasDeSymbole_jamaisLAvion() {
        assertThat(TransportModeLabels.label(null)).isNull();
        assertThat(TransportModeLabels.symbol(null)).isNull();
        assertThat(TransportModeLabels.symbol(TransportMode.OTHER)).isNull();
    }
}
