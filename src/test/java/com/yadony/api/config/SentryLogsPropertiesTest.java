package com.yadony.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.logback.SentryAppender;
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

    /**
     * Le starter Sentry ne tire PAS `sentry-logback` (vérifié sur 7.22.4 et 8.16.0).
     * Sans cette dépendance explicite, `SentryLogbackAppenderAutoConfiguration` est
     * inerte (`@ConditionalOnClass(SentryAppender.class)`) : aucun log ne quitte
     * l'application et les propriétés `sentry.logging.*` ci-dessus ne pilotent rien.
     * C'est exactement l'état constaté en staging le 2026-09-14, onglet Logs vide.
     */
    @Test
    void logbackAppenderIsOnTheClasspath() {
        assertThat(SentryAppender.class.getName()).isEqualTo("io.sentry.logback.SentryAppender");
    }
}
