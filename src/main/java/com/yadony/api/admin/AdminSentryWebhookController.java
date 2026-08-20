package com.yadony.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.stripe.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminSentryWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AdminSentryWebhookController.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String webhookSecret;
    private final ObjectMapper objectMapper;
    private final AdminAlertService adminAlert;

    public AdminSentryWebhookController(
            @Value("${yadony.admin.sentry-webhook.secret:}") String webhookSecret,
            ObjectMapper objectMapper,
            AdminAlertService adminAlert) {
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
        this.adminAlert = adminAlert;
    }

    @PostMapping("/sentry-webhook")
    public ResponseEntity<Void> handle(
            @RequestBody String rawBody,
            @RequestHeader(value = "sentry-hook-signature", required = false) String signature) {
        if (!isValidSignature(rawBody, signature)) {
            log.warn("Sentry webhook rejected: invalid signature");
            return ResponseEntity.status(401).build();
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode issue = root.path("data").path("issue");
            if (issue.isMissingNode() || issue.isNull()) {
                return ResponseEntity.ok().build();
            }

            String action = root.path("action").asText("unknown");
            String title = issue.path("title").asText("Sentry issue");
            String culprit = issue.path("culprit").asText("");
            String level = issue.path("level").asText("error");
            String shortId = issue.path("shortId").asText("");
            String permalink = issue.path("permalink").asText("");
            String project = issue.path("project").path("slug").asText("");

            String code = "SENTRY_ISSUE_" + action.toUpperCase(Locale.ROOT);
            String detail = culprit.isBlank() ? title : title + " — " + culprit;

            Map<String, Object> context = new LinkedHashMap<>();
            context.put("action", action);
            context.put("level", level);
            if (!project.isBlank()) context.put("project", project);
            if (!shortId.isBlank()) context.put("issueId", shortId);
            if (!permalink.isBlank()) context.put("lien", permalink);

            adminAlert.raise(code, detail, context);
        } catch (Exception e) {
            log.warn("Could not process Sentry webhook payload: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private boolean isValidSignature(String body, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] computed = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            log.error("HMAC verification failed unexpectedly: {}", e.getMessage());
            return false;
        }
    }
}
