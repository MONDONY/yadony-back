package com.yadony.api.admin;

import com.yadony.api.smsotp.SmsOtpDeviceNumberHoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Alerte du back-office (Alertes, cloche, Sentry, Telegram) quand un même téléphone SANS compte
 * demande des codes SMS pour plusieurs numéros différents : demande du propriétaire après le
 * pompage de SMS du 08/11/2026. Alerte seulement, il n'y a pas de compte à suspendre.
 *
 * <p>Écouteur SYNCHRONE dans la transaction d'envoi du code, souvent annulée ensuite (numéro
 * sans compte refusé) : l'alerte s'écrit dans la transaction de {@link AdminAlertEscalator}, et
 * rien ici ne doit lever, le demandeur doit recevoir la réponse normale.
 */
@Component
class SmsOtpDeviceAlertListener {

    static final String ALERT_PREFIX = "SMS_OTP_DEVICE_";

    private static final Logger log = LoggerFactory.getLogger(SmsOtpDeviceAlertListener.class);

    private final AdminAlertEscalator alerts;

    SmsOtpDeviceAlertListener(AdminAlertEscalator alerts) {
        this.alerts = alerts;
    }

    @EventListener
    public void onDeviceNumberHopping(SmsOtpDeviceNumberHoppingEvent event) {
        String detail = "Un téléphone sans compte (" + describe(event.deviceKey()) + ") a demandé des codes SMS "
                + "pour " + event.distinctNumbers() + " numéros différents en 24 h (seuil " + event.threshold()
                + "). Possible robot ou pompage de SMS. Si « Code SMS réservé aux numéros déjà inscrits » est "
                + "activé, aucun SMS n'est parti vers les numéros sans compte. Rien n'est bloqué : il n'y a "
                + "pas de compte à suspendre.";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("deviceKey", event.deviceKey());
        context.put("distinctNumbers", event.distinctNumbers());
        context.put("threshold", event.threshold());
        try {
            alerts.raiseOnce(ALERT_PREFIX + truncate(event.deviceKey()), "WARN", detail, context);
        } catch (RuntimeException e) {
            log.error("Alerte back-office impossible pour le téléphone {} (codes SMS, {} numéros)",
                    event.deviceKey(), event.distinctNumbers(), e);
        }
    }

    /** Lisible dans l'alerte : identifiant d'appareil de l'app, ou session invitée. */
    private static String describe(String deviceKey) {
        if (deviceKey.startsWith("device:")) {
            return "appareil " + deviceKey.substring("device:".length());
        }
        if (deviceKey.startsWith("guest:")) {
            return "session invitée " + deviceKey.substring("guest:".length());
        }
        return deviceKey;
    }

    /** {@code admin_alerts.type} fait 60 caractères. */
    private static String truncate(String deviceKey) {
        int room = AdminAlertEscalator.TYPE_MAX_LENGTH - ALERT_PREFIX.length();
        return deviceKey.length() <= room ? deviceKey : deviceKey.substring(0, room);
    }
}
