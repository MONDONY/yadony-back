package com.yadony.api.smsotp;

import com.yadony.api.notifications.SmsService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Rend visible, au démarrage et en continu, le fait que l'envoi de SMS OTP n'est
 * pas configuré.
 *
 * <p>Contrairement à {@code EmailOtpConfigurationGuard}, le téléphone reste le
 * canal d'authentification PRINCIPAL de Yadony : si {@code app.sms.enabled} est
 * faux ou qu'aucun provider (Africa's Talking / Twilio) n'est configuré en
 * production, toute nouvelle inscription/connexion par téléphone est cassée —
 * pas juste un canal secondaire dégradé. On signale donc fort en prod, sans
 * jamais bloquer le démarrage (même raison qu'{@code EmailOtpConfigurationGuard} :
 * un statut DOWN ferait boucler le conteneur en restart, une indisponibilité
 * totale pire que la panne partielle qu'on cherche à signaler).
 */
@Component("smsOtp")
public class SmsOtpConfigurationGuard implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SmsOtpConfigurationGuard.class);

    private final SmsService smsService;
    private final List<String> activeProfiles;

    @Value("${app.sms.africastalking.api-key:}")
    private String atApiKey;

    @Value("${app.sms.twilio.account-sid:}")
    private String twilioAccountSid;

    public SmsOtpConfigurationGuard(SmsService smsService, Environment environment) {
        this.smsService = smsService;
        this.activeProfiles = Arrays.asList(environment.getActiveProfiles());
    }

    boolean isTwilioConfigured() {
        return twilioAccountSid != null && !twilioAccountSid.isBlank();
    }

    boolean isAfricasTalkingConfigured() {
        return atApiKey != null && !atApiKey.isBlank();
    }

    @PostConstruct
    void reportConfigurationAtStartup() {
        boolean smsEnabled = smsService.isEnabled();
        boolean configured = isTwilioConfigured() || isAfricasTalkingConfigured();

        if (smsEnabled && configured) {
            return;
        }

        if (activeProfiles.contains("prod")) {
            if (!smsEnabled) {
                // Canal fermé délibérément (SMS_ENABLED=false) : l'app masque le
                // CTA téléphone via GET /config/sms-enabled — situation normale,
                // pas une erreur à remonter en alerte à chaque démarrage.
                log.warn("⚠️  Canal SMS OTP désactivé en production (SMS_ENABLED=false) — "
                        + "la connexion par téléphone est masquée dans l'app.");
                return;
            }
            log.error("❌ SMS OTP activé mais mal configuré en production (twilio={}, "
                    + "africasTalking={}) : aucun code OTP ne partira, la connexion par "
                    + "téléphone est INUTILISABLE. Vérifier TWILIO_ACCOUNT_SID, "
                    + "AT_API_KEY dans le .env de l'hôte, puis redémarrer.",
                    isTwilioConfigured(), isAfricasTalkingConfigured());
            return;
        }

        log.warn("⚠️  SMS OTP non configuré (smsEnabled={}) — aucun SMS ne sera réellement "
                + "envoyé. Les codes OTP apparaîtront uniquement dans ces logs.", smsEnabled);
    }

    /** Toujours {@code UP}, même mal configuré — voir Javadoc de la classe. */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("smsEnabled", smsService.isEnabled())
                .withDetail("twilioConfigured", isTwilioConfigured())
                .withDetail("africasTalkingConfigured", isAfricasTalkingConfigured())
                .build();
    }
}
