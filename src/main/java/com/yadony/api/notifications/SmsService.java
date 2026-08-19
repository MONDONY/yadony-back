package com.yadony.api.notifications;

import com.yadony.api.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final String AT_URL = "https://api.africastalking.com/version1/messaging";

    // La property n'est plus lue ici : PlatformSettingsService en est le seul lecteur, et
    // s'en sert comme valeur de repli quand la ligne de table manque. Deux lecteurs
    // indépendants de la même property rouvriraient précisément la divergence corrigée.

    @Value("${app.sms.africastalking.api-key:}")
    private String atApiKey;

    @Value("${app.sms.africastalking.username:sandbox}")
    private String atUsername;

    // Indicatifs des corridors couverts par Africa's Talking (connexions opérateur
    // directes, tarifs locaux) : Sénégal, Côte d'Ivoire, Mali, Cameroun. Tout autre
    // indicatif (France comprise) part directement sur Twilio — Africa's Talking
    // n'a pas vocation à couvrir l'Europe, et un aller-retour voué à l'échec avant
    // le fallback ajouterait latence et risque d'échec silencieux pour rien.
    @Value("${app.sms.africastalking.corridor-calling-codes:221,225,223,237}")
    private List<String> atCorridorCallingCodes = List.of("221", "225", "223", "237");

    @Value("${app.sms.twilio.account-sid:}")
    private String twilioAccountSid;

    @Value("${app.sms.twilio.auth-token:}")
    private String twilioAuthToken;

    @Value("${app.sms.twilio.from:}")
    private String twilioFrom;

    private final RestTemplate restTemplate;
    private final PlatformSettingsService settings;

    public SmsService(RestTemplate restTemplate, PlatformSettingsService settings) {
        this.restTemplate = restTemplate;
        this.settings = settings;
    }

    /**
     * Source unique de l'état des SMS, la table d'abord.
     *
     * <p>Lu depuis {@code platform_settings} et non depuis la property : {@code SmsOtpService}
     * garde l'envoi des codes de connexion sur cette valeur. Si elle restait sur la property,
     * couper les SMS depuis le back-office pendant un incident ne couperait rien, et les
     * réactiver proposerait un parcours de connexion qui échouerait systématiquement — alors
     * que {@code /config/sms-enabled} annoncerait l'inverse à l'application mobile.
     *
     * <p>{@code PlatformSettingsService} retombe sur la property quand la ligne manque.
     */
    public boolean isEnabled() {
        return settings.smsEnabled();
    }

    public void send(String phoneNumber, String message) {
        // Même source que isEnabled() : une coupure décidée depuis le back-office doit
        // arrêter l'envoi réel, pas seulement masquer un bouton.
        if (!isEnabled()) {
            log.info("[SMS-DEV] To=*** | [message redacted]");
            return;
        }
        if (isAfricasTalkingCorridor(phoneNumber)) {
            if (!sendViaAfricasTalking(phoneNumber, message)) {
                log.warn("[SMS] Africa's Talking failed, falling back to Twilio");
                sendViaTwilio(phoneNumber, message);
            }
        } else {
            sendViaTwilio(phoneNumber, message);
        }
    }

    /** Numéro E.164 (ex: +2250712345678) dont l'indicatif fait partie des corridors Africa's Talking. */
    boolean isAfricasTalkingCorridor(String phoneNumber) {
        if (phoneNumber == null || !phoneNumber.startsWith("+")) {
            return false;
        }
        String digits = phoneNumber.substring(1);
        return atCorridorCallingCodes.stream().anyMatch(digits::startsWith);
    }

    boolean sendViaAfricasTalking(String phoneNumber, String message) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("apiKey", atApiKey);
            headers.set("Accept", "application/json");
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("username", atUsername);
            body.add("to", phoneNumber);
            body.add("message", message);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    AT_URL, new HttpEntity<>(body, headers), String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[SMS] Africa's Talking: delivered successfully");
                return true;
            }
            log.warn("[SMS] Africa's Talking returned HTTP {}", response.getStatusCode());
            return false;
        } catch (Exception e) {
            log.warn("[SMS] Africa's Talking error: {}", e.getMessage());
            return false;
        }
    }

    void sendViaTwilio(String phoneNumber, String message) {
        try {
            String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilioAccountSid + "/Messages.json";

            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(twilioAccountSid, twilioAuthToken);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("To", phoneNumber);
            body.add("From", twilioFrom);
            body.add("Body", message);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[SMS] Twilio: delivered successfully");
            } else {
                log.error("[SMS] Twilio returned HTTP {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[SMS] Twilio error: {}", e.getMessage());
        }
    }
}