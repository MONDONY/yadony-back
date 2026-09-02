package com.yadony.api.smsotp;

import com.yadony.api.notifications.SmsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SmsOtpConfigurationGuard")
class SmsOtpConfigurationGuardTest {

    private SmsOtpConfigurationGuard guard(boolean smsEnabled, String twilioSid, String atKey,
                                            String... profiles) {
        SmsService smsService = mock(SmsService.class);
        when(smsService.isEnabled()).thenReturn(smsEnabled);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        SmsOtpConfigurationGuard g = new SmsOtpConfigurationGuard(smsService, env);
        ReflectionTestUtils.setField(g, "twilioAccountSid", twilioSid);
        ReflectionTestUtils.setField(g, "atApiKey", atKey);
        return g;
    }

    /**
     * Le démarrage ne doit JAMAIS être bloqué, quel que soit le profil — même raison
     * qu'EmailOtpConfigurationGuard : un DOWN ferait boucler le conteneur en restart.
     */
    @Test
    void neverBlocksStartup_whateverTheProfile() {
        for (String profile : new String[] {"prod", "staging", "dev", "test"}) {
            assertThatCode(() -> guard(false, "", "", profile).reportConfigurationAtStartup())
                    .as("profil %s", profile)
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> guard(true, "AC123", "atkey", "prod").reportConfigurationAtStartup())
                .doesNotThrowAnyException();
    }

    /**
     * SMS_ENABLED=false en prod est un choix produit (canal fermé, CTA masqué
     * dans l'app via /config/sms-enabled) : le boot ne doit plus crier ERROR
     * à chaque démarrage — c'était un faux positif d'alerte permanent.
     */
    @Test
    void deliberatelyDisabledChannelInProd_logsWarnNotError() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SmsOtpConfigurationGuard.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            guard(false, "AC123", "", "prod").reportConfigurationAtStartup();
            assertThat(appender.list)
                    .noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** Activé mais sans aucun fournisseur : vraie erreur de config, ERROR attendu. */
    @Test
    void enabledButUnconfiguredInProd_logsError() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SmsOtpConfigurationGuard.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            guard(true, "", "", "prod").reportConfigurationAtStartup();
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void healthStaysUp_evenWhenNotConfigured() {
        assertThat(guard(false, "", "", "prod").health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void healthReportsMissingConfigInDetails() {
        assertThat(guard(false, "", "", "prod").health().getDetails())
                .containsEntry("smsEnabled", false)
                .containsEntry("twilioConfigured", false)
                .containsEntry("africasTalkingConfigured", false);
    }

    @Test
    void healthReportsConfiguredProvidersInDetails() {
        assertThat(guard(true, "AC123", "atkey", "prod").health().getDetails())
                .containsEntry("smsEnabled", true)
                .containsEntry("twilioConfigured", true)
                .containsEntry("africasTalkingConfigured", true);
    }

    @Test
    void isTwilioConfigured_blankCountsAsMissing() {
        assertThat(guard(true, "", "atkey", "prod").isTwilioConfigured()).isFalse();
        assertThat(guard(true, "AC123", "atkey", "prod").isTwilioConfigured()).isTrue();
    }

    @Test
    void isAfricasTalkingConfigured_blankCountsAsMissing() {
        assertThat(guard(true, "AC123", "", "prod").isAfricasTalkingConfigured()).isFalse();
        assertThat(guard(true, "AC123", "atkey", "prod").isAfricasTalkingConfigured()).isTrue();
    }
}
