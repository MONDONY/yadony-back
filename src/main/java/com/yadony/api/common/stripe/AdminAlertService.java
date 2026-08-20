package com.yadony.api.common.stripe;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
public class AdminAlertService {
    private static final Logger log = LoggerFactory.getLogger(AdminAlertService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneOffset.UTC);

    private final RestClient restClient;
    private final String telegramBotToken;
    private final String telegramChatId;
    private final String environment;

    public AdminAlertService() {
        this(RestClient.create(), null, null, "");
    }

    @Autowired
    public AdminAlertService(
            @Value("${yadony.telegram.bot-token:}") String telegramBotToken,
            @Value("${yadony.telegram.chat-id:}") String telegramChatId,
            @Value("${spring.profiles.active:}") String environment) {
        this(RestClient.create(), telegramBotToken, telegramChatId, environment);
    }

    AdminAlertService(RestClient restClient, String telegramBotToken, String telegramChatId, String environment) {
        this.restClient = restClient;
        this.telegramBotToken = telegramBotToken;
        this.telegramChatId = telegramChatId;
        this.environment = (environment == null || environment.isBlank()) ? "local" : environment;
    }

    public void raise(String code, String detail, Map<String, Object> context) {
        log.error("[ADMIN ALERT] {} — {} | context={}", code, detail, context);
        Sentry.withScope(scope -> {
            context.forEach((k, v) -> scope.setExtra(k, String.valueOf(v)));
            Sentry.captureMessage("[ADMIN ALERT] " + code + " — " + detail);
        });
        sendTelegram(code, detail, context);
    }

    private void sendTelegram(String code, String detail, Map<String, Object> context) {
        if (telegramBotToken == null || telegramBotToken.isBlank()
                || telegramChatId == null || telegramChatId.isBlank()) {
            return;
        }
        try {
            restClient.post()
                    .uri("https://api.telegram.org/bot{token}/sendMessage", telegramBotToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", telegramChatId, "text", buildMessage(code, detail, context)))
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
        if (code.equals("STRIPE_ACCOUNT_DEAUTHORIZED") || code.equals("STRIPE_CAPABILITY_LOST")) return "🏦";
        if (code.contains("CHARGEBACK") || code.contains("FRAUD")) return "⚠️";
        if (code.contains("REFUND")) return "💸";
        if (code.contains("PAYOUT") || code.contains("TRANSFER")) return "💰";
        if (code.contains("DEAD_LETTER")) return "☠️";
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
            default -> "Alerte système";
        };
    }
}
