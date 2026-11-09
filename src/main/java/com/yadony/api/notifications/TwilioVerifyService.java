package com.yadony.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Codes de connexion par Twilio Verify, pour les seuls indicatifs où un SMS classique ne passe
 * pas.
 *
 * <p>Vers un mobile américain ou canadien, les opérateurs bloquent les SMS applicatifs envoyés
 * depuis un numéro local non enregistré A2P 10DLC (erreur 30034). Twilio accepte l'envoi puis
 * l'opérateur le jette : {@link SmsService} journalise un succès et le code n'arrive jamais (un
 * testeur de Houston le 27/09). Verify envoie depuis des expéditeurs que Twilio a lui-même
 * enregistrés, génère le code et le vérifie : yadony ne le connaît jamais.
 *
 * <p>Réservé à {@code app.sms.twilio.verify-calling-codes} (défaut {@code 1}, zone NANP : États-Unis,
 * Canada, Caraïbes). La France et les corridors africains gardent leur route, moins chère.
 * Sans {@code TWILIO_VERIFY_SERVICE_SID}, rien ne change : {@link #handles} rend faux.
 */
@Service
public class TwilioVerifyService {

    private static final Logger log = LoggerFactory.getLogger(TwilioVerifyService.class);

    private static final String VERIFY_URL = "https://verify.twilio.com/v2/Services/";
    private static final Pattern APPROVED = Pattern.compile("\"status\"\\s*:\\s*\"approved\"");

    /**
     * Refus Verify qui désignent le numéro saisi : 60200 paramètre invalide (numéro mal formé),
     * 60205 numéro non joignable par SMS (fixe), plus les codes Messaging que Verify relaie.
     */
    static final Set<Integer> INVALID_RECIPIENT_CODES = Set.of(60200, 60205, 21211, 21614);

    private final RestTemplate restTemplate;

    @Value("${app.sms.twilio.account-sid:}")
    private String accountSid;

    @Value("${app.sms.twilio.auth-token:}")
    private String authToken;

    @Value("${app.sms.twilio.verify-service-sid:}")
    private String serviceSid;

    @Value("${app.sms.twilio.verify-calling-codes:1}")
    private List<String> callingCodes;

    public TwilioVerifyService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return notBlank(accountSid) && notBlank(authToken) && notBlank(serviceSid);
    }

    /** Vrai si ce numéro E.164 doit passer par Verify plutôt que par un SMS classique. */
    public boolean handles(String phoneNumber) {
        if (!isConfigured() || phoneNumber == null || !phoneNumber.startsWith("+")) {
            return false;
        }
        String digits = phoneNumber.substring(1);
        return callingCodes.stream().anyMatch(digits::startsWith);
    }

    /**
     * Demande à Twilio d'envoyer un code à ce numéro.
     *
     * @param locale langue du SMS ({@code fr}, {@code en}) : Verify rédige lui-même le message
     * @param appHash empreinte SMS Retriever de l'app Android, ajoutée par Verify au SMS ;
     *                {@code null} pour n'en mettre aucune
     * @throws InvalidSmsRecipientException si le numéro lui-même est refusé
     * @throws VerifyUnavailableException pour toute autre panne
     */
    public void start(String phoneNumber, String locale, String appHash) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("To", phoneNumber);
        body.add("Channel", "sms");
        if (notBlank(locale)) {
            body.add("Locale", locale);
        }
        if (notBlank(appHash)) {
            body.add("AppHash", appHash);
        }
        try {
            restTemplate.postForEntity(VERIFY_URL + serviceSid + "/Verifications",
                    new HttpEntity<>(body, headers()), String.class);
        } catch (HttpClientErrorException e) {
            Integer code = SmsService.twilioErrorCode(e.getResponseBodyAsString());
            if (code != null && INVALID_RECIPIENT_CODES.contains(code)) {
                log.warn("[Verify] Twilio rejected the recipient number (code {})", code);
                throw new InvalidSmsRecipientException(code);
            }
            log.error("[Verify] Twilio refused the verification (HTTP {}, code {})",
                    e.getStatusCode().value(), code);
            throw new VerifyUnavailableException();
        } catch (RuntimeException e) {
            log.error("[Verify] Twilio unreachable ({})", e.getClass().getSimpleName());
            throw new VerifyUnavailableException();
        }
    }

    /**
     * Vrai si Twilio approuve ce code pour ce numéro.
     *
     * <p>Un 404 (vérification expirée, déjà approuvée ou inconnue) et un 429 (trop d'essais sur
     * cette vérification) valent « code refusé » : le client doit en redemander un. Seules les
     * autres pannes lèvent.
     *
     * @throws VerifyUnavailableException si Twilio est injoignable ou refuse la requête
     */
    public boolean check(String phoneNumber, String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("To", phoneNumber);
        body.add("Code", code);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    VERIFY_URL + serviceSid + "/VerificationCheck",
                    new HttpEntity<>(body, headers()), String.class);
            return response.getBody() != null && APPROVED.matcher(response.getBody()).find();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND
                    || e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                return false;
            }
            log.error("[Verify] Twilio refused the check (HTTP {}, code {})",
                    e.getStatusCode().value(), SmsService.twilioErrorCode(e.getResponseBodyAsString()));
            throw new VerifyUnavailableException();
        } catch (RuntimeException e) {
            log.error("[Verify] Twilio unreachable ({})", e.getClass().getSimpleName());
            throw new VerifyUnavailableException();
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(accountSid, authToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return headers;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Twilio Verify injoignable ou mal configuré : l'appelant répond 503. */
    public static class VerifyUnavailableException extends RuntimeException {
        public VerifyUnavailableException() {
            super("Twilio Verify indisponible");
        }
    }
}
