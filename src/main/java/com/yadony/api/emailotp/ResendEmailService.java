package com.yadony.api.emailotp;

import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class ResendEmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    private final RestClient restClient;
    private final TemplateEngine templateEngine;
    private final String fromAddress;
    private final String otpTemplate;
    private final boolean emailSendingConfigured;
    private final boolean devProfile;
    private final boolean prodProfile;

    @Autowired
    public ResendEmailService(EmailOtpProperties props, Environment env, TemplateEngine templateEngine) {
        this.fromAddress = props.getFromAddress();
        this.otpTemplate = props.getOtpTemplate();
        this.templateEngine = templateEngine;
        this.emailSendingConfigured = props.getResendApiKey() != null && !props.getResendApiKey().isBlank();
        var profiles = Arrays.asList(env.getActiveProfiles());
        this.devProfile = profiles.contains("dev");
        this.prodProfile = profiles.contains("prod");
        this.restClient = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getResendApiKey())
                .build();
    }

    ResendEmailService(String fromAddress, String otpTemplate, RestClient restClient, TemplateEngine templateEngine) {
        this(fromAddress, otpTemplate, restClient, templateEngine, true, false, false);
    }

    ResendEmailService(
            String fromAddress,
            String otpTemplate,
            RestClient restClient,
            TemplateEngine templateEngine,
            boolean emailSendingConfigured,
            boolean devProfile,
            boolean prodProfile) {
        this.fromAddress = fromAddress;
        this.otpTemplate = otpTemplate;
        this.restClient = restClient;
        this.templateEngine = templateEngine;
        this.emailSendingConfigured = emailSendingConfigured;
        this.devProfile = devProfile;
        this.prodProfile = prodProfile;
    }

    public void sendOtp(String to, String code) {
        if (!emailSendingConfigured) {
            if (prodProfile) {
                throw new YadonyBusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE, "email-service-not-configured",
                        "Email Service Not Configured",
                        "L'envoi des emails n'est pas configuré");
            }
            log.warn("📧 [LOCAL] Code OTP pour {} : {}", maskEmail(to), code);
            return;
        }

        String textBody = String.format(otpTemplate, code);
        Map<String, Object> payload = Map.of(
                "from", fromAddress,
                "to", List.of(to),
                "subject", "Ton code Yadony",
                "html", renderOtpHtml(code),
                "text", textBody
        );
        try {
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // PII : ne jamais logguer l'email complet en prod (accumulation RGPD dans
            // la chaîne de logs sur incident Resend récurrent). On masque.
            log.error("Resend API error sending OTP to {}: {}", maskEmail(to), e.getMessage());
            if (devProfile) {
                // En dev, l'envoi réel peut échouer (domaine Resend non vérifié).
                // On logge le code pour permettre la connexion locale sans email réel.
                log.warn("📧 [DEV] Code OTP pour {} : {}", to, code);
                return;
            }
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "email-service-error",
                    "Email Service Error", "L'envoi de l'email a échoué, veuillez réessayer");
        }
    }

    private String renderOtpHtml(String code) {
        Context context = new Context();
        context.setVariable("otpCode", code);
        return templateEngine.process("email-otp", context);
    }

    /** Masque une adresse email pour les logs : {@code j***@domain.com}. */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "(vide)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
