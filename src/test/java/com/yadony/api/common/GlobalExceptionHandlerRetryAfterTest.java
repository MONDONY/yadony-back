package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler — Retry-After sur les refus de débit")
class GlobalExceptionHandlerRetryAfterTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.resolver());

    @Test
    @DisplayName("retryAfterSeconds dans les propriétés → en-tête Retry-After et propriété conservée")
    void retryAfterHeader() {
        var response = handler.handleBusiness(new YadonyBusinessException(
                HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", "Too Many Requests", "Patiente",
                Map.of("retryAfterSeconds", 42L)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        assertThat(response.getBody().getProperties()).containsEntry("retryAfterSeconds", 42L);
    }

    @Test
    @DisplayName("sans retryAfterSeconds → pas d'en-tête Retry-After")
    void noRetryAfterHeader() {
        var response = handler.handleBusiness(new YadonyBusinessException(
                HttpStatus.TOO_MANY_REQUESTS, "rate-limit", "Too Many Requests", "Patiente"));

        assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
