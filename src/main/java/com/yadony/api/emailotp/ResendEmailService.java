package com.yadony.api.emailotp;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.gppro.events.GpProDecisionEvent;
import com.yadony.api.notifications.NotificationEmailPolicy;
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
    private final boolean emailSendingConfigured;
    private final boolean devProfile;
    private final boolean prodProfile;

    @Autowired
    public ResendEmailService(EmailOtpProperties props, Environment env, TemplateEngine templateEngine) {
        this.fromAddress = props.getFromAddress();
        this.templateEngine = templateEngine;
        this.emailSendingConfigured = props.getResendApiKey() != null && !props.getResendApiKey().isBlank();
        var profiles = Arrays.asList(env.getActiveProfiles());
        this.devProfile = profiles.contains("dev");
        this.prodProfile = profiles.contains("prod");
        this.restClient = RestClient.builder()
                // Sans délai, un Resend muet bloquait l'envoi de l'OTP (et sa transaction).
                .requestFactory(com.yadony.api.config.OutboundHttpTimeouts.requestFactory())
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getResendApiKey())
                .build();
    }

    ResendEmailService(String fromAddress, RestClient restClient, TemplateEngine templateEngine) {
        this(fromAddress, restClient, templateEngine, true, false, false);
    }

    ResendEmailService(
            String fromAddress,
            RestClient restClient,
            TemplateEngine templateEngine,
            boolean emailSendingConfigured,
            boolean devProfile,
            boolean prodProfile) {
        this.fromAddress = fromAddress;
        this.restClient = restClient;
        this.templateEngine = templateEngine;
        this.emailSendingConfigured = emailSendingConfigured;
        this.devProfile = devProfile;
        this.prodProfile = prodProfile;
    }

    public void sendOtp(String to, String code, Messages m) {
        // dev uniquement (liste blanche) : « pas prod » incluait staging, dont les logs
        // partent vers Loki et Sentry avec de vrais testeurs derrière les adresses.
        if (devProfile) {
            log.warn("📧 [DEV] Code OTP pour {} : {}", maskEmail(to), code);
        }

        if (!emailSendingConfigured) {
            if (prodProfile) {
                throw new YadonyBusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE, "email-service-not-configured",
                        "Email Service Not Configured",
                        "L'envoi des emails n'est pas configuré");
            }
            return;
        }

        Map<String, Object> payload = Map.of(
                "from", fromAddress,
                "to", List.of(to),
                "subject", m.get("email.otp.subject"),
                "html", renderOtpHtml(code, m),
                "text", m.get("email.otp.text", code)
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
                return;
            }
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "email-service-error",
                    "Email Service Error", "L'envoi de l'email a échoué, veuillez réessayer");
        }
    }

    /**
     * E-mail de décision GP Pro (acceptée, refusée, badge retiré). Contrairement à
     * {@link #sendOtp}, ne lève jamais : l'e-mail double un push déjà parti, un échec
     * d'envoi est seulement journalisé (adresse masquée).
     */
    public void sendGpProDecision(String to, GpProDecisionEvent.Kind kind, String reason, Messages m) {
        try {
            if (!emailSendingConfigured) {
                return;
            }
            String key = "email.gp-pro." + kind.name().toLowerCase(java.util.Locale.ROOT);
            String subject = m.get(key + ".subject");
            String text = kind == GpProDecisionEvent.Kind.APPROVED
                    ? m.get(key + ".text")
                    : m.get(key + ".text", reason == null ? "" : reason);
            Map<String, Object> payload = Map.of(
                    "from", fromAddress,
                    "to", List.of(to),
                    "subject", subject,
                    "html", renderGpProDecisionHtml(subject, text, kind == GpProDecisionEvent.Kind.APPROVED, m),
                    "text", text
            );
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("Resend: échec de l'e-mail de décision GP Pro vers {}: {}", maskEmail(to), e.getClass().getSimpleName());
        }
    }

    /** Bouton des e-mails de notification : le site mène à l'application. */
    static final String APP_URL = "https://yadony.com";

    /**
     * E-mail d'une notification (reçu, décision, récapitulatif, alerte) : titre et texte de
     * la notification, puis la ligne de sa famille. Comme {@link #sendGpProDecision}, ne lève
     * jamais : la notification est déjà dans l'app, un échec est seulement journalisé.
     */
    public void sendNotification(String to, NotificationEmailPolicy.Kind kind, String type,
                                 String title, String body, Messages m) {
        try {
            if (!emailSendingConfigured) {
                return;
            }
            String family = "email.notif.kind." + kind.name().toLowerCase(java.util.Locale.ROOT);
            String detail = m.get(family + ".detail");
            if (NotificationEmailPolicy.hasTypeDetail(type)) {
                detail = detail + " " + m.get("email.notif.type." + type + ".detail");
            }
            String text = String.join("\n\n", title, body == null ? "" : body, detail,
                    m.get("email.notif.cta") + " : " + APP_URL);
            Map<String, Object> payload = Map.of(
                    "from", fromAddress,
                    "to", List.of(to),
                    "subject", title,
                    "html", renderNotificationHtml(kind, family, title, body, detail, m),
                    "text", text
            );
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("Resend: échec de l'e-mail de notification {} vers {}: {}", type, maskEmail(to), e.getClass().getSimpleName());
        }
    }

    private String renderNotificationHtml(NotificationEmailPolicy.Kind kind, String family, String heading,
                                          String body, String detail, Messages m) {
        Context context = new Context(m.locale());
        context.setVariable("eyebrow", m.get(family + ".eyebrow"));
        context.setVariable("heading", heading);
        context.setVariable("body", body);
        context.setVariable("detail", detail);
        context.setVariable("optional", kind.pref().isPresent());
        context.setVariable("appUrl", APP_URL);
        context.setVariable("lang", m.language().code());
        return templateEngine.process("email-notification", context);
    }

    private String renderGpProDecisionHtml(String heading, String body, boolean approved, Messages m) {
        Context context = new Context(m.locale());
        context.setVariable("heading", heading);
        context.setVariable("body", body);
        context.setVariable("lang", m.language().code());
        context.setVariable("approved", approved);
        return templateEngine.process("email-gp-pro-decision", context);
    }

    private String renderOtpHtml(String code, Messages m) {
        Context context = new Context(m.locale());
        context.setVariable("otpCode", code);
        context.setVariable("lang", m.language().code());
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
