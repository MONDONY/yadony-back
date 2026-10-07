package com.yadony.api.common.stripe;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import org.springframework.stereotype.Component;

/**
 * Une alerte admin ne doit créer qu'UNE issue Sentry : celle de
 * {@link AdminAlertService#raise} ({@code captureMessage}, empreinte par code).
 *
 * <p>Le {@code log.error("[ADMIN ALERT] …")} d'un incident est gardé (métrique Grafana
 * {@code logback_events_total{level="error"}}, onglet Sentry Logs), mais l'appender Logback
 * en faisait aussi un événement (minimum-event-level: error) : deux issues par alerte
 * (YADONY-BACK-STAGING-J et K), puis deux échos {@code SENTRY_ISSUE_CREATED} sur Telegram.
 * Ce callback écarte cet événement-là, et lui seul : le logger est celui du service, le
 * message porte le préfixe, et le tag {@link #TAG_ADMIN_ALERT} — posé uniquement par la
 * capture explicite — est absent. Les Sentry Logs ({@code beforeSendLog}) ne passent pas ici.
 *
 * <p>Détecté par le starter Sentry Spring Boot (bean {@code BeforeSendCallback} unique).
 */
@Component
public class AdminAlertSentryFilter implements SentryOptions.BeforeSendCallback {

    /** Tag posé par {@link AdminAlertService#raise} sur sa propre capture. */
    public static final String TAG_ADMIN_ALERT = "admin_alert";

    static final String LOGGER_ADMIN_ALERT = AdminAlertService.class.getName();

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        return estLogDAlerteAdmin(event) ? null : event;
    }

    private static boolean estLogDAlerteAdmin(SentryEvent event) {
        if (!LOGGER_ADMIN_ALERT.equals(event.getLogger())) {
            return false;
        }
        if (event.getTag(TAG_ADMIN_ALERT) != null) {
            return false;
        }
        Message message = event.getMessage();
        if (message == null) {
            return false;
        }
        return commenceParPrefixe(message.getFormatted()) || commenceParPrefixe(message.getMessage());
    }

    private static boolean commenceParPrefixe(String texte) {
        return texte != null && texte.startsWith(AdminAlertService.PREFIXE_ALERTE);
    }
}
