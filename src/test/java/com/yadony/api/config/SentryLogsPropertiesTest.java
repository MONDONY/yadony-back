package com.yadony.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.spring.boot.jakarta.SentryProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Garde le contrat « seuls WARN et ERROR partent en Sentry Logs » posé dans
 * application.yml. Sans ce test, une régression (bloc supprimé, seuil rabaissé à
 * INFO/DEBUG) passerait inaperçue jusqu'à l'explosion du quota Sentry en staging,
 * où com.yadony.api logue en DEBUG.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SentryLogsPropertiesTest {

    @Autowired
    private SentryProperties sentryProperties;

    @Test
    void sentryLogsEnabled() {
        assertThat(sentryProperties.getLogs().isEnabled()).isTrue();
    }

    @Test
    void onlyWarnAndAboveForwardedAsSentryLogs() {
        assertThat(sentryProperties.getLogging().getMinimumLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void errorsStayEventsAndInfoStaysBreadcrumbs() {
        assertThat(sentryProperties.getLogging().getMinimumEventLevel()).isEqualTo(Level.ERROR);
        assertThat(sentryProperties.getLogging().getMinimumBreadcrumbLevel()).isEqualTo(Level.INFO);
    }
}
