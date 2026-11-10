package com.yadony.api.videomodal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Versions de l'app : lecture stricte (campagne) et tolérante (app)")
class VideoModalAppVersionsTest {

    @Test
    @DisplayName("strict : x.y.z seulement")
    void strict() {
        assertThat(VideoModalAppVersions.parseStrict("1.0.6")).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 0, 6));
        assertThat(VideoModalAppVersions.parseStrict(" 12.34.56 ")).isPresent();
        assertThat(VideoModalAppVersions.parseStrict(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.0", "1.0.6.1", "1.0.x", "v1.0.6", "1.0.6+1053", "-1.0.0", "10000.0.0", "1..2"})
    @DisplayName("strict : formes mal formées refusées")
    void strictRefuse(String raw) {
        assertThat(VideoModalAppVersions.parseStrict(raw)).isEmpty();
    }

    @Test
    @DisplayName("tolérant : build, préfixe v, version courte ; illisible = inconnue")
    void lenient() {
        assertThat(VideoModalAppVersions.parseLenient("1.0.6+1053")).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 0, 6));
        assertThat(VideoModalAppVersions.parseLenient("v2.1")).hasValueSatisfying(v -> assertThat(v).containsExactly(2, 1, 0));
        assertThat(VideoModalAppVersions.parseLenient("3")).hasValueSatisfying(v -> assertThat(v).containsExactly(3, 0, 0));
        assertThat(VideoModalAppVersions.parseLenient("1.0.6-beta")).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 0, 6));
        assertThat(VideoModalAppVersions.parseLenient("abc")).isEmpty();
        assertThat(VideoModalAppVersions.parseLenient("  ")).isEmpty();
        assertThat(VideoModalAppVersions.parseLenient(null)).isEmpty();
    }

    @Test
    @DisplayName("comparaison numérique : 1.0.10 > 1.0.9, égalité à 0")
    void compare() {
        int[] a = {1, 0, 10};
        int[] b = {1, 0, 9};
        assertThat(VideoModalAppVersions.compare(a, b)).isPositive();
        assertThat(VideoModalAppVersions.compare(b, a)).isNegative();
        assertThat(VideoModalAppVersions.compare(a, new int[] {1, 0, 10})).isZero();
        assertThat(VideoModalAppVersions.compare(new int[] {2, 0, 0}, new int[] {1, 99, 99})).isPositive();
        assertThat(VideoModalAppVersions.compare(new int[] {1, 1, 0}, new int[] {1, 2, 0})).isNegative();
    }
}
