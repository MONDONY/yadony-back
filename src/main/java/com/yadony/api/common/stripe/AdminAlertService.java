package com.yadony.api.common.stripe;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class AdminAlertService {
    private static final Logger log = LoggerFactory.getLogger(AdminAlertService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Gravité d'une alerte. Elle décide du niveau de log, donc de la métrique
     * {@code logback_events_total{level="error"}} sur laquelle repose la règle Grafana
     * « Pic d'erreurs serveur ». Journaliser un évènement métier en ERROR déclenchait
     * une alerte critique « erreurs serveur » à la quatrième en cinq minutes : quatre
     * tickets de support suffisaient.
     */
    enum Gravite {
        /** Évènement métier normal. Ni ERROR, ni Sentry, notification Telegram muette. */
        INFO,
        /** Anomalie à regarder, déjà comptée ailleurs ou non bloquante. */
        AVERTISSEMENT,
        /** Incident : argent, fraude, ou traitement définitivement perdu. */
        INCIDENT
    }

    private static final Set<String> CODES_INFO = Set.of(
            "SUPPORT_TICKET_CREATED",
            "STRIPE_CHARGEBACK_CLOSED",
            "KYC_IDENTITY_CANCELED");

    private static final Set<String> CODES_AVERTISSEMENT = Set.of(
            "SENTRY_ISSUE_CREATED",
            "SENTRY_ISSUE_UNRESOLVED",
            "KYC_IDENTITY_REJECTED",
            "EXCHANGE_RATE_SYNC_REJECTED");

    /** Préfixe des alertes nées d'un webhook Sentry. Voir {@link #raise}. */
    private static final String PREFIXE_SENTRY = "SENTRY_ISSUE_";

    private final RestClient restClient;
    private final String telegramBotToken;
    private final String telegramChatId;
    private final String environment;

    public AdminAlertService() {
        this(withTimeouts(), null, null, "");
    }

    @Autowired
    public AdminAlertService(
            @Value("${yadony.telegram.bot-token:}") String telegramBotToken,
            @Value("${yadony.telegram.chat-id:}") String telegramChatId,
            @Value("${spring.profiles.active:}") String environment) {
        this(withTimeouts(), telegramBotToken, telegramChatId, environment);
    }

    // Timeouts durs : RestClient.create() n'en a AUCUN (défauts JDK infinis) — une
    // alerte ne doit jamais pendre le thread qui la lève (2026-09-02, fetch BCE).
    // Surtout pas SimpleClientHttpRequestFactory : sur HttpURLConnection, un POST
    // redirigé est réémis SANS son corps, et l'alerte part vide (2026-09-05, Didit).
    private static RestClient withTimeouts() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofSeconds(10))))
                .build();
    }

    AdminAlertService(RestClient restClient, String telegramBotToken, String telegramChatId, String environment) {
        this.restClient = restClient;
        this.telegramBotToken = telegramBotToken;
        this.telegramChatId = telegramChatId;
        this.environment = (environment == null || environment.isBlank()) ? "local" : environment;
    }

    public void raise(String code, String detail, Map<String, Object> context) {
        Gravite gravite = graviteDe(code);
        journaliser(gravite, code, detail, context);

        // Une alerte née d'un webhook Sentry ne doit JAMAIS repartir vers Sentry :
        // captureMessage y créerait une issue, dont le webhook rappellerait raise(),
        // dont le captureMessage créerait une issue… Le texte contient l'identifiant
        // de l'issue précédente, donc chaque tour produit une empreinte différente et
        // rien ne dédoublonne la boucle.
        boolean remonteVersSentry = gravite != Gravite.INFO && !code.startsWith(PREFIXE_SENTRY);
        if (remonteVersSentry) {
            Sentry.withScope(scope -> {
                context.forEach((k, v) -> scope.setExtra(k, String.valueOf(v)));
                Sentry.captureMessage("[ADMIN ALERT] " + code + " — " + detail);
            });
        }

        sendTelegram(code, detail, context, gravite);
    }

    static Gravite graviteDe(String code) {
        if (code == null) return Gravite.INCIDENT;
        if (CODES_INFO.contains(code)) return Gravite.INFO;
        if (CODES_AVERTISSEMENT.contains(code)) return Gravite.AVERTISSEMENT;
        return Gravite.INCIDENT;
    }

    private void journaliser(Gravite gravite, String code, String detail, Map<String, Object> context) {
        switch (gravite) {
            case INFO -> log.info("[ADMIN ALERT] {} — {} | context={}", code, detail, context);
            case AVERTISSEMENT -> log.warn("[ADMIN ALERT] {} — {} | context={}", code, detail, context);
            case INCIDENT -> log.error("[ADMIN ALERT] {} — {} | context={}", code, detail, context);
        }
    }

    private void sendTelegram(String code, String detail, Map<String, Object> context, Gravite gravite) {
        if (telegramBotToken == null || telegramBotToken.isBlank()
                || telegramChatId == null || telegramChatId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> corps = new LinkedHashMap<>();
            corps.put("chat_id", telegramChatId);
            corps.put("text", buildMessage(code, detail, context));
            // Un évènement métier ne réveille personne ; un incident, si.
            corps.put("disable_notification", gravite == Gravite.INFO);
            restClient.post()
                    .uri("https://api.telegram.org/bot{token}/sendMessage", telegramBotToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corps)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Telegram alert failed for {}: {}", code, e.getMessage());
        }
    }

    private String buildMessage(String code, String detail, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(emojiFor(code)).append(' ').append(titleFor(code)).append('\n');
        sb.append("Code : ").append(code).append('\n');
        sb.append(detail).append('\n');
        if (context != null && !context.isEmpty()) {
            sb.append('\n');
            context.forEach((key, value) -> sb.append("• ").append(key).append(" : ").append(value).append('\n'));
        }
        sb.append('\n');
        sb.append("🌍 Environnement : ").append(environment).append('\n');
        sb.append("🕒 ").append(TIMESTAMP_FORMAT.format(Instant.now())).append(" UTC");
        return sb.toString();
    }

    private String emojiFor(String code) {
        if (code.startsWith("KYC_")) return "🪪";
        if (code.startsWith("SENTRY_ISSUE_")) return "🐛";
        if (code.startsWith("SUPPORT_TICKET_")) return "💬";
        if (code.equals("STRIPE_ACCOUNT_DEAUTHORIZED") || code.equals("STRIPE_CAPABILITY_LOST")) return "🏦";
        if (code.contains("CHARGEBACK") || code.contains("FRAUD")) return "⚠️";
        if (code.contains("REFUND")) return "💸";
        if (code.contains("PAYOUT") || code.contains("TRANSFER")) return "💰";
        if (code.contains("DEAD_LETTER")) return "☠️";
        if (code.startsWith("EXCHANGE_RATE_")) return "💱";
        return "🚨";
    }

    private String titleFor(String code) {
        return switch (code) {
            case "KYC_IDENTITY_REJECTED" -> "Vérification KYC échouée";
            case "KYC_IDENTITY_CANCELED" -> "Vérification KYC annulée";
            case "STRIPE_ACCOUNT_DEAUTHORIZED" -> "Compte Stripe Connect déconnecté";
            case "STRIPE_CAPABILITY_LOST" -> "Capacité Stripe perdue";
            case "STRIPE_PAYOUT_FAILED" -> "Virement voyageur échoué";
            case "STRIPE_TRANSFER_REVERSED" -> "Transfert Stripe annulé";
            case "STRIPE_REFUND_FAILED" -> "Remboursement échoué";
            case "STRIPE_EARLY_FRAUD_WARNING" -> "Alerte fraude Stripe";
            case "STRIPE_CHARGEBACK_OPENED" -> "Litige carte ouvert";
            case "STRIPE_CHARGEBACK_CLOSED" -> "Litige carte clôturé";
            case "CHARGEBACK_TRANSFER_BLOCKED" -> "Libération escrow bloquée";
            case "REFUND_AFTER_RELEASE" -> "Remboursement après versement";
            case "STRIPE_DEAD_LETTER" -> "Event Stripe en échec définitif";
            case "BILLING_CHECKOUT_UNRESOLVED_USER" -> "Paiement PRO encaissé sans utilisateur identifiable";
            case "SENTRY_ISSUE_CREATED" -> "Nouvelle erreur Sentry";
            case "SENTRY_ISSUE_UNRESOLVED" -> "Régression Sentry (erreur redevenue active)";
            case "SUPPORT_TICKET_CREATED" -> "Nouveau ticket support";
            case "EXCHANGE_RATE_SYNC_FAILED" -> "Synchronisation des taux de change échouée";
            case "EXCHANGE_RATE_SYNC_REJECTED" -> "Taux de change refusés (variation anormale)";
            default -> "Alerte système";
        };
    }
}
