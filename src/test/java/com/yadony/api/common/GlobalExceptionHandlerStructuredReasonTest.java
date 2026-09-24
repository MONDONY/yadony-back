package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerStructuredReasonTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.resolver());

    @Test
    void responseStatus_withStructuredReason_mapsToTypeAndTitle() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.GONE, "request/expired");

        ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
        ProblemDetail pd = resp.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getType().toString()).endsWith("request/expired");
        assertThat(pd.getTitle()).isEqualTo("Expired");
    }

    @Test
    void responseStatus_withSimpleReason_setsGenericType() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.NOT_FOUND, "not-found");

        ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex);

        ProblemDetail pd = resp.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getType().toString()).endsWith("/generic");
    }

    @Test
    void responseStatus_withNestedSlash_titlesFromSecondSegment() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.CONFLICT, "negotiation/duplicate-thread");

        ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex);

        ProblemDetail pd = resp.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getType().toString()).endsWith("negotiation/duplicate-thread");
        assertThat(pd.getTitle()).isEqualTo("Duplicate thread");
    }

    @Test
    void responseStatus_withNullReason_setsGenericType() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);

        ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex);

        ProblemDetail pd = resp.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getType().toString()).endsWith("/generic");
    }
}
