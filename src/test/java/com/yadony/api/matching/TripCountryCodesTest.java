package com.yadony.api.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Règles des codes pays d'un trajet (FLUTTER-EH). */
class TripCountryCodesTest {

    private static final Map<String, String> CITIES = Map.of("Paris", "FR", "Abidjan", "ci");
    private static final Function<String, Optional<String>> LOOKUP =
            name -> Optional.ofNullable(CITIES.get(name));

    @Test
    @DisplayName("création : le code fourni prime et sort en majuscules")
    void resolve_providedCodeWins() {
        assertThat(TripCountryCodes.resolve(" bj ", "Paris", LOOKUP)).isEqualTo("BJ");
    }

    @Test
    @DisplayName("création : code absent, vide ou invalide → déduit de la ville")
    void resolve_missingCode_fromCity() {
        assertThat(TripCountryCodes.resolve(null, "Paris", LOOKUP)).isEqualTo("FR");
        assertThat(TripCountryCodes.resolve("  ", " Paris ", LOOKUP)).isEqualTo("FR");
        assertThat(TripCountryCodes.resolve("FRA", "Abidjan", LOOKUP)).isEqualTo("CI");
    }

    @Test
    @DisplayName("création : ville inconnue, vide ou nulle → null")
    void resolve_unknownCity_null() {
        assertThat(TripCountryCodes.resolve(null, "Atlantide", LOOKUP)).isNull();
        assertThat(TripCountryCodes.resolve(null, " ", LOOKUP)).isNull();
        assertThat(TripCountryCodes.resolve(null, null, LOOKUP)).isNull();
        assertThat(TripCountryCodes.resolve(null, "Paris", name -> null)).isNull();
    }

    @Test
    @DisplayName("modification : le code fourni prime")
    void resolveOnUpdate_providedCodeWins() {
        assertThat(TripCountryCodes.resolveOnUpdate("sn", "Paris", "Paris", "FR", LOOKUP))
                .isEqualTo("SN");
    }

    @Test
    @DisplayName("modification sans code, même ville (casse et espaces ignorés) → code existant conservé")
    void resolveOnUpdate_sameCity_keepsCurrent() {
        assertThat(TripCountryCodes.resolveOnUpdate(null, " paris", "Paris", "fr", name -> {
            throw new AssertionError("pas de recherche attendue");
        })).isEqualTo("FR");
    }

    @Test
    @DisplayName("modification sans code, même ville mais code existant absent → déduit de la ville")
    void resolveOnUpdate_sameCityWithoutCurrent_fromCity() {
        assertThat(TripCountryCodes.resolveOnUpdate(null, "Paris", "Paris", null, LOOKUP))
                .isEqualTo("FR");
    }

    @Test
    @DisplayName("modification sans code, ville changée → déduit de la nouvelle ville")
    void resolveOnUpdate_changedCity_fromNewCity() {
        assertThat(TripCountryCodes.resolveOnUpdate(null, "Abidjan", "Paris", "FR", LOOKUP))
                .isEqualTo("CI");
        assertThat(TripCountryCodes.resolveOnUpdate(null, "Atlantide", null, "FR", LOOKUP))
                .isNull();
    }
}
