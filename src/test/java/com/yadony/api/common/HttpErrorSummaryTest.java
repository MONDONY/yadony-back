package com.yadony.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpErrorSummaryTest {

    private static final String SECRET = "DUMMYSECRET123";

    @Test
    void ioError_keepsTheTypesButNeverTheUrl() {
        // Format réel de Spring 6.2 : le message reprend l'URI complète.
        ResourceAccessException e = new ResourceAccessException(
                "I/O error on GET request for \"https://maps.googleapis.com/maps/api/geocode/json?key="
                        + SECRET + "\": Connection refused", new ConnectException("Connection refused"));

        String summary = HttpErrorSummary.of(e);

        assertThat(summary).isEqualTo("ResourceAccessException (ConnectException)");
        assertThat(summary).doesNotContain(SECRET).doesNotContain("googleapis");
    }

    @Test
    void httpStatusError_keepsOnlyTheStatus() {
        HttpClientErrorException e = HttpClientErrorException.create(
                "401 on POST request for \"https://api.telegram.org/bot" + SECRET + "/sendMessage\"",
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(HttpErrorSummary.of(e)).isEqualTo("HTTP 401");
    }

    @Test
    void errorWithoutCause_keepsItsType() {
        assertThat(HttpErrorSummary.of(new ResourceAccessException("I/O error ... key=" + SECRET)))
                .isEqualTo("ResourceAccessException");
    }
}
