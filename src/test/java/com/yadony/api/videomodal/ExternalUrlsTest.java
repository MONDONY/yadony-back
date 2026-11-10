package com.yadony.api.videomodal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Liens externes des actions INFO : https uniquement")
class ExternalUrlsTest {

    @Test
    @DisplayName("https valide accepté, espaces de bord retirés, schéma insensible à la casse")
    void accepte() {
        assertThat(ExternalUrls.requireHttps("https://yadony.com/aide?x=1#a")).isEqualTo("https://yadony.com/aide?x=1#a");
        assertThat(ExternalUrls.requireHttps("  https://yadony.com  ")).isEqualTo("https://yadony.com");
        assertThat(ExternalUrls.requireHttps("HTTPS://yadony.com")).isEqualTo("HTTPS://yadony.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "http://yadony.com",
            "javascript:alert(1)",
            "data:text/html;base64,AAAA",
            "mailto:a@b.fr",
            "ftp://yadony.com",
            "yadony.com/aide",
            "//yadony.com",
            "https://",
            "https:///chemin",
            "https://yadony .com",
            "https://yadony.com/\u0000x",
            "https://exa mple.com",
            "https://[invalide"
    })
    @DisplayName("refusé : http, javascript:, data:, mailto:, relatif, sans hôte, espaces, invalide")
    void refuse(String raw) {
        assertThat(ExternalUrls.requireHttps(raw)).isNull();
    }

    @Test
    @DisplayName("2000 caractères acceptés, 2001 refusés")
    void longueur() {
        String base = "https://yadony.com/";
        String ok = base + "a".repeat(ExternalUrls.MAX_LENGTH - base.length());
        assertThat(ok).hasSize(2000);
        assertThat(ExternalUrls.requireHttps(ok)).isEqualTo(ok);
        assertThat(ExternalUrls.requireHttps(ok + "a")).isNull();
    }
}
