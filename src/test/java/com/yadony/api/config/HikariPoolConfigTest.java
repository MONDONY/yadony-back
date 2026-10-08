package com.yadony.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YADONY-BACK-STAGING-11 : une connexion JDBC morte n'était détectée qu'au maxLifetime
 * par défaut (30 min). Le keepalive et le maxLifetime réduit sont posés dans
 * {@code application.yml} pour tous les profils ; ce test lit les fichiers réellement
 * embarqués pour qu'un profil ne les écrase pas en silence.
 */
@DisplayName("Pool Hikari — keepalive et maxLifetime communs à tous les profils")
class HikariPoolConfigTest {

    private static final String KEEPALIVE = "spring.datasource.hikari.keepalive-time";
    private static final String MAX_LIFETIME = "spring.datasource.hikari.max-lifetime";

    private static PropertySource<?> load(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                file, new FileSystemResource("src/main/resources/" + file));
        assertThat(sources).as("%s doit être un YAML valide et non vide", file).isNotEmpty();
        return sources.get(0);
    }

    @Test
    @DisplayName("application.yml : keepalive 60 s, maxLifetime 15 min, keepalive < maxLifetime")
    void baseConfigDefinesKeepaliveAndMaxLifetime() throws IOException {
        PropertySource<?> base = load("application.yml");

        long keepalive = Long.parseLong(String.valueOf(base.getProperty(KEEPALIVE)));
        long maxLifetime = Long.parseLong(String.valueOf(base.getProperty(MAX_LIFETIME)));

        assertThat(keepalive).isEqualTo(60_000L);
        assertThat(maxLifetime).isEqualTo(900_000L);
        // Hikari ignore un keepalive >= maxLifetime (et un keepalive < 30 s).
        assertThat(keepalive).isGreaterThanOrEqualTo(30_000L).isLessThan(maxLifetime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-staging.yml", "application-prod.yml"})
    @DisplayName("aucun profil ne surcharge keepalive-time ni max-lifetime")
    void profilesDoNotOverride(String file) throws IOException {
        PropertySource<?> profile = load(file);

        assertThat(profile.getProperty(KEEPALIVE)).as(file).isNull();
        assertThat(profile.getProperty(MAX_LIFETIME)).as(file).isNull();
    }
}
