package com.yadony.api.assistant.provider;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaseUrlValidatorTest {

    @Test
    void retireLeSlashFinalEtLesEspaces() {
        assertThat(BaseUrlValidator.normalize("  https://api.groq.com/openai/v1/  "))
                .isEqualTo("https://api.groq.com/openai/v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.groq.com/v1", "https://localhost/v1", "https://127.0.0.1/v1", "https://10.0.0.5",
            "https://[::1]/v1", "https://user:pass@api.groq.com", "https://api.groq.com/v1?x=1",
            "https://api.groq.com/v1#a", "https://api .groq.com", "ftp://api.groq.com", "https://", "",
            "https://metadata.google.internal/v1", "https://printer.local",
            "https://0177.0.0.1/v1", "https://0127.0.0.1", "https://127.1", "https://0x7f.0.0.1",
            "https://localhost.", "https://localhost.:443", "https://foo.localhost.", "https://printer.local.",
            "https://metadata.google.internal.",
            "https://api.groq.com:22/v1", "https://api.groq.com:8443", "https://api.groq.com:99999/v1",
            "https://api.groq.com:/v1", "https://exämple.com"})
    void refuse(String url) {
        assertThatThrownBy(() -> BaseUrlValidator.normalize(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepteLePort443() {
        assertThat(BaseUrlValidator.normalize("https://api.groq.com:443/openai/v1"))
                .isEqualTo("https://api.groq.com:443/openai/v1");
    }

    @Test
    void refuseUnAutrePortAvecMessage() {
        assertThatThrownBy(() -> BaseUrlValidator.normalize("https://api.groq.com:22/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Seul le port 443 est accepté.");
        assertThatThrownBy(() -> BaseUrlValidator.normalize("https://api.groq.com:/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Seul le port 443 est accepté.");
    }

    @Test
    void refuseUnNomIdnAvecMessage() {
        assertThatThrownBy(() -> BaseUrlValidator.normalize("https://exämple.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Utilisez le nom de domaine en caractères simples (punycode).");
    }

    @Test
    void refuseUneAdresseTropLongue() {
        assertThatThrownBy(() -> BaseUrlValidator.normalize("https://a.com/" + "x".repeat(250)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refuseNull() {
        assertThatThrownBy(() -> BaseUrlValidator.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
