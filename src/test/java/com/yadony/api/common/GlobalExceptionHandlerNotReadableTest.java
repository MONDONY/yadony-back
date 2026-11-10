package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corps JSON illisible : le message brut de Jackson recopie le jeton fautif (un NIF tapé sans guillemets, par
 * exemple). Il ne doit jamais atteindre les journaux ni Sentry : avertissement avec les classes d'erreur seulement.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerNotReadableTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.resolver());

    @Test
    void corpsIllisible_400SansJamaisJournaliserLeJetonBrut(CapturedOutput output) {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unrecognized token 'AB12CD34NIF': was expecting (JSON String)",
                new IllegalStateException("Unrecognized token 'AB12CD34NIF'"), (HttpInputMessage) null);

        ResponseEntity<ProblemDetail> response = handler.handleNotReadable(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType().toString()).endsWith("malformed-request");
        assertThat(response.getBody().getDetail()).doesNotContain("AB12CD34NIF");
        assertThat(output.getAll()).doesNotContain("AB12CD34NIF").doesNotContain("Unrecognized token")
                .contains("WARN").contains("HttpMessageNotReadableException").contains("IllegalStateException")
                .doesNotContain("ERROR");
    }
}
