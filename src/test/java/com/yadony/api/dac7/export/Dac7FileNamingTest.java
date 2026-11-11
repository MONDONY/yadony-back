package com.yadony.api.dac7.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Nom du fichier DPI-DAC7 (règle de nommage DGFiP, revenus 2026)")
class Dac7FileNamingTest {

    private static final LocalDateTime AT = LocalDateTime.of(2027, 1, 31, 15, 12, 20);

    @Test
    @DisplayName("exemple de la DGFiP : premier fichier 2026 constitué le 31/01/2027 à 15:12:20")
    void exempleDgfip() {
        assertThat(Dac7FileNaming.fileName(2026, "135789101", 0, AT, true))
                .isEqualTo("DPIDAC7_2026_135789101_000_20270131151220.xml.gz.gpg");
        assertThat(Dac7FileNaming.fileName(2026, "135789101", 1, AT, false))
                .isEqualTo("DPIDAC7_2026_135789101_001_20270131151220.xml");
        assertThat(Dac7FileNaming.fileName(2026, "135789101", 0, AT, true)).matches("[A-Za-z0-9_.]+");
    }

    @Test
    @DisplayName("SIREN, ordre et millésime contrôlés")
    void controles() {
        assertThatThrownBy(() -> Dac7FileNaming.fileName(2026, "12345678", 0, AT, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dac7FileNaming.fileName(2026, "123 456 78", 0, AT, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dac7FileNaming.fileName(2026, null, 0, AT, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dac7FileNaming.fileName(2026, "135789101", 1000, AT, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dac7FileNaming.fileName(2026, "135789101", -1, AT, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dac7FileNaming.fileName(2022, "135789101", 0, AT, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
