package com.yadony.api.common.stripe;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le log.error d'une alerte admin ne doit plus devenir une seconde issue Sentry ; la
 * capture explicite de {@link AdminAlertService#raise} et toute autre erreur passent.
 */
class AdminAlertSentryFilterTest {

    private final AdminAlertSentryFilter filter = new AdminAlertSentryFilter();

    /** Événement tel que le construit l'appender Logback de Sentry. */
    private static SentryEvent logEvent(String logger, String pattern, String formatted) {
        SentryEvent event = new SentryEvent();
        event.setLevel(SentryLevel.ERROR);
        event.setLogger(logger);
        Message message = new Message();
        message.setMessage(pattern);
        message.setFormatted(formatted);
        event.setMessage(message);
        return event;
    }

    @Test
    void logErrorDAlerteAdmin_estEcarte() {
        SentryEvent event = logEvent(AdminAlertService.class.getName(),
                "[ADMIN ALERT] {} — {} | context={}",
                "[ADMIN ALERT] MONEY_INVARIANT — Solde incohérent | context={}");

        assertThat(filter.execute(event, new Hint())).isNull();
    }

    @Test
    void logErrorDAlerteAdmin_reconnuParLeSeulPatron() {
        SentryEvent event = logEvent(AdminAlertService.class.getName(),
                "[ADMIN ALERT] {} — {} | context={}", null);

        assertThat(filter.execute(event, new Hint())).isNull();
    }

    @Test
    void captureExpliciteDeLAlerte_passe() {
        // Sentry.captureMessage : pas de logger, tag admin_alert posé dans le scope.
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setFormatted("[ADMIN ALERT] MONEY_INVARIANT — Solde incohérent");
        event.setMessage(message);
        event.setTag(AdminAlertSentryFilter.TAG_ADMIN_ALERT, "MONEY_INVARIANT");

        assertThat(filter.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    void evenementTagueDuMemeLogger_passe() {
        SentryEvent event = logEvent(AdminAlertService.class.getName(),
                "[ADMIN ALERT] {}", "[ADMIN ALERT] X");
        event.setTag(AdminAlertSentryFilter.TAG_ADMIN_ALERT, "X");

        assertThat(filter.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    void autreErreurDuMemeLogger_passe() {
        SentryEvent event = logEvent(AdminAlertService.class.getName(),
                "Telegram alert failed for {}: {}", "Telegram alert failed for X: timeout");

        assertThat(filter.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    void evenementSansMessageDuMemeLogger_passe() {
        SentryEvent event = new SentryEvent();
        event.setLogger(AdminAlertService.class.getName());

        assertThat(filter.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    void erreurPrefixeeDUnAutreLogger_passe() {
        SentryEvent event = logEvent("com.yadony.api.payments.PaymentService",
                "[ADMIN ALERT] copie", "[ADMIN ALERT] copie");

        assertThat(filter.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    void exceptionApplicative_passe() {
        SentryEvent event = new SentryEvent(new IllegalStateException("boom"));

        assertThat(filter.execute(event, new Hint())).isSameAs(event);
    }
}
