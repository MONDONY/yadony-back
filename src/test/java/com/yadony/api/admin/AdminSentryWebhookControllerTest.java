package com.yadony.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminSentryWebhookControllerTest {

    private static final String SECRET = "test-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AdminAlertService adminAlert;
    private AdminSentryWebhookController controller;

    @BeforeEach
    void setUp() {
        adminAlert = mock(AdminAlertService.class);
        controller = new AdminSentryWebhookController(SECRET, objectMapper, adminAlert);
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void handle_withInvalidSignature_returns401_andDoesNotAlert() {
        String body = "{\"action\":\"created\",\"data\":{\"issue\":{\"title\":\"Boom\"}}}";

        ResponseEntity<Void> response = controller.handle(body, "wrong-signature");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(adminAlert);
    }

    @Test
    void handle_withMissingSignature_returns401() {
        String body = "{\"action\":\"created\",\"data\":{\"issue\":{\"title\":\"Boom\"}}}";

        ResponseEntity<Void> response = controller.handle(body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(adminAlert);
    }

    @Test
    void handle_withValidSignature_raisesAlertWithIssueDetails() throws Exception {
        String body = "{\"action\":\"created\",\"data\":{\"issue\":{"
                + "\"title\":\"NullPointerException in BidService\","
                + "\"culprit\":\"BidService.accept\","
                + "\"level\":\"error\","
                + "\"shortId\":\"YADONY-42\","
                + "\"permalink\":\"https://sentry.io/issues/42\","
                + "\"project\":{\"slug\":\"yadony-back\"}"
                + "}}}";
        String signature = sign(body);

        ResponseEntity<Void> response = controller.handle(body, signature);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(adminAlert).raise(
                eq("SENTRY_ISSUE_CREATED"),
                eq("NullPointerException in BidService — BidService.accept"),
                anyMap());
    }

    @Test
    void handle_withValidSignature_missingIssue_doesNotAlert() throws Exception {
        String body = "{\"action\":\"created\",\"data\":{}}";
        String signature = sign(body);

        ResponseEntity<Void> response = controller.handle(body, signature);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(adminAlert);
    }

    @Test
    void handle_withValidSignature_unresolvedAction_buildsUnresolvedCode() throws Exception {
        String body = "{\"action\":\"unresolved\",\"data\":{\"issue\":{\"title\":\"Regression\"}}}";
        String signature = sign(body);

        controller.handle(body, signature);

        verify(adminAlert).raise(eq("SENTRY_ISSUE_UNRESOLVED"), anyString(), anyMap());
    }

    @Test
    void handle_withMalformedJson_returns200_andDoesNotThrow() throws Exception {
        String body = "not-json";
        String signature = sign(body);

        ResponseEntity<Void> response = controller.handle(body, signature);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(adminAlert);
    }
}
