package com.yadony.api.dac7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Dac7Properties : conservation du NIF au moins deux années civiles")
class Dac7PropertiesTest {

    @Test
    @DisplayName("allumé, une conservation sous 2 ans refuse le démarrage (la purge effacerait le NIF avant janvier)")
    void allumeSousDeuxAns_refuse() {
        assertThatThrownBy(() -> new Dac7Properties(true, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention-years");
        assertThatThrownBy(() -> new Dac7Properties(true, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("éteint, une valeur trop basse est ramenée à 2 sans bloquer le démarrage")
    void eteintSousDeuxAns_rameneADeux() {
        assertThat(new Dac7Properties(false, null, 1).retentionYearsOrDefault()).isEqualTo(2);
        assertThat(new Dac7Properties(null, null, 0).retentionYearsOrDefault()).isEqualTo(2);
    }

    @Test
    @DisplayName("défaut 2, valeur plus haute gardée")
    void defautEtValeurHaute() {
        assertThat(new Dac7Properties(true, null, null).retentionYearsOrDefault()).isEqualTo(2);
        assertThat(new Dac7Properties(true, null, 2).retentionYearsOrDefault()).isEqualTo(2);
        assertThat(new Dac7Properties(true, null, 6).retentionYearsOrDefault()).isEqualTo(6);
    }
}
