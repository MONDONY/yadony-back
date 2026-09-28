package com.yadony.api.kyc.provider.didit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DiditConfigurationGuard")
class DiditConfigurationGuardTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(DiditConfigurationGuard.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static DiditConfigurationGuard guard(String apiKey, String workflowId,
                                                 String webhookSecret, Boolean diditEnabled,
                                                 String... profiles) {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        if (diditEnabled == null) {
            when(settings.kycDiditEnabled()).thenThrow(new IllegalStateException("base injoignable"));
        } else {
            when(settings.kycDiditEnabled()).thenReturn(diditEnabled);
        }
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        DiditProperties properties = new DiditProperties(
                "https://verification.didit.me", apiKey, workflowId, webhookSecret, "live");
        return new DiditConfigurationGuard(properties, settings, env);
    }

    private boolean loggedAt(Level level) {
        return appender.list.stream().anyMatch(e -> e.getLevel() == level);
    }

    /** Le cas du 27/09 : Didit actif en staging, DIDIT_WORKFLOW_ID vide. */
    @Test
    void emptyWorkflowIdWhileDiditActiveInStaging_logsErrorNamingTheVariable() {
        guard("key", "", "secret", true, "staging").reportConfigurationAtStartup();

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("DIDIT_WORKFLOW_ID")
                        && !e.getFormattedMessage().contains("DIDIT_API_KEY"));
    }

    @Test
    void missingConfigWhileDiditActiveInProd_logsError() {
        guard("", "wf", "secret", true, "prod").reportConfigurationAtStartup();

        assertThat(loggedAt(Level.ERROR)).isTrue();
    }

    /** Stripe Identity sert les sessions : l'absence de Didit n'est pas une panne. */
    @Test
    void missingConfigWhileStripeIsActive_logsWarnOnly() {
        guard("", "", "", false, "prod").reportConfigurationAtStartup();

        assertThat(loggedAt(Level.ERROR)).isFalse();
        assertThat(loggedAt(Level.WARN)).isTrue();
    }

    @Test
    void missingConfigOutsideDeployedProfiles_logsWarnOnly() {
        guard("", "", "", true, "dev").reportConfigurationAtStartup();

        assertThat(loggedAt(Level.ERROR)).isFalse();
        assertThat(loggedAt(Level.WARN)).isTrue();
    }

    /** Réglage illisible : dans le doute, on ne tait pas une vraie panne. */
    @Test
    void unreadableSettingInStaging_treatedAsActive() {
        guard("key", "", "secret", null, "staging").reportConfigurationAtStartup();

        assertThat(loggedAt(Level.ERROR)).isTrue();
    }

    @Test
    void completeConfig_logsNothing() {
        guard("key", "wf", "secret", true, "prod").reportConfigurationAtStartup();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void neverBlocksStartup_whateverTheProfile() {
        for (String profile : new String[] {"prod", "staging", "dev", "test"}) {
            assertThatCode(() -> guard(null, null, null, null, profile)
                    .reportConfigurationAtStartup())
                    .as("profil %s", profile)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void missingVariables_listsBlankAndNullValues() {
        assertThat(guard(null, " ", "secret", true, "prod").missingVariables())
                .containsExactly("DIDIT_API_KEY", "DIDIT_WORKFLOW_ID");
        assertThat(guard("key", "wf", "secret", true, "prod").missingVariables()).isEmpty();
    }

    @Test
    void healthStaysUpAndReportsEachVariable() {
        var health = guard("key", "", "secret", true, "prod").health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("diditEnabled", "true")
                .containsEntry("apiKeyConfigured", true)
                .containsEntry("workflowIdConfigured", false)
                .containsEntry("webhookSecretConfigured", true);
    }

    @Test
    void healthStaysUpWhenSettingUnreadable() {
        var health = guard("key", "wf", "secret", null, "prod").health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("diditEnabled", "null");
    }
}
