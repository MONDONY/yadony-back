package com.yadony.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.dac7.dto.TaxInfoRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Corps JSON illisible : le message brut de Jackson recopie le jeton fautif (un NIF tapé sans guillemets, par
 * exemple). Il ne doit jamais atteindre les journaux ni Sentry. Le journal garde de quoi localiser l'erreur : la route
 * (méthode et motif), la classe d'erreur et le chemin du champ fautif, sans aucune valeur ni extrait du corps.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerNotReadableTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.resolver());

    private static MockHttpServletRequest put(String uri, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", uri);
        request.setQueryString("token=QSECRET");
        if (pattern != null) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        }
        return request;
    }

    @Test
    void jetonIllisible_400_routeEtClasse_sansJamaisLeJeton(CapturedOutput output) {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unrecognized token 'AB12CD34NIF': was expecting (JSON String)",
                new IllegalStateException("Unrecognized token 'AB12CD34NIF'"), (HttpInputMessage) null);

        ResponseEntity<ProblemDetail> response = handler.handleNotReadable(ex,
                put("/api/v1/travelers/me/tax-info", null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType().toString()).endsWith("malformed-request");
        assertThat(response.getBody().getTitle()).isEqualTo("Bad Request");
        assertThat(response.getBody().getDetail()).isEqualTo("Malformed request payload");
        assertThat(output.getAll()).doesNotContain("AB12CD34NIF").doesNotContain("Unrecognized token")
                .doesNotContain("QSECRET")
                .contains("WARN").contains("PUT /api/v1/travelers/me/tax-info")
                .contains("HttpMessageNotReadableException").contains("IllegalStateException")
                .doesNotContain("ERROR");
    }

    @Test
    void mauvaisTypeDeChamp_cheminDuChampAuJournal_sansSaValeur(CapturedOutput output) {
        Throwable jackson = catchThrowable(() -> new ObjectMapper().readValue(
                "{\"taxIdentificationNumber\": [\"SECRET-NIF-42\"], \"taxCountry\": \"FR\"}", TaxInfoRequest.class));
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error: "
                + jackson.getMessage(), jackson, (HttpInputMessage) null);

        handler.handleNotReadable(ex, put("/api/v1/travelers/me/tax-info", "/travelers/me/tax-info"));

        assertThat(output.getAll()).doesNotContain("SECRET-NIF-42").doesNotContain("QSECRET")
                .contains("PUT /travelers/me/tax-info").contains("taxIdentificationNumber")
                .contains("MismatchedInputException");
    }
}
