package com.yadony.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestCorrelationFilter — corrélation des requêtes")
class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("en-tête X-Request-Id fourni → repris dans le MDC et renvoyé tel quel")
    void incomingHeader_isReusedInMdcAndEchoedInResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestCorrelationFilter.HEADER, "req_123.ok-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInChain));

        assertThat(seenInChain.get()).isEqualTo("req_123.ok-42");
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("req_123.ok-42");
    }

    @Test
    @DisplayName("en-tête absent → identifiant généré, visible dans la chaîne et dans la réponse")
    void missingHeader_generatesAnIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInChain));

        String generated = response.getHeader(RequestCorrelationFilter.HEADER);
        assertThat(generated).matches("[0-9a-f]{12}");
        assertThat(seenInChain.get()).isEqualTo(generated);
    }

    @Test
    @DisplayName("deux requêtes sans en-tête → deux identifiants différents")
    void missingHeader_generatesDistinctIdentifiers() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/a"), first, capturingChain(new AtomicReference<>()));
        filter.doFilter(new MockHttpServletRequest("GET", "/b"), second, capturingChain(new AtomicReference<>()));

        assertThat(first.getHeader(RequestCorrelationFilter.HEADER))
                .isNotEqualTo(second.getHeader(RequestCorrelationFilter.HEADER));
    }

    @ParameterizedTest(name = "en-tête « {0} » → remplacé")
    @ValueSource(strings = {"bad header with spaces", "with\nnewline", "", "   ", "é-accent", "<script>"})
    @DisplayName("en-tête non sûr → remplacé par un identifiant généré")
    void unsafeHeader_isReplacedByGeneratedIdentifier(String unsafe) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestCorrelationFilter.HEADER, unsafe);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain(new AtomicReference<>()));

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER))
                .matches("[0-9a-f]{12}")
                .isNotEqualTo(unsafe);
    }

    @Test
    @DisplayName("en-tête trop long (> 64) → remplacé")
    void tooLongHeader_isReplaced() throws Exception {
        String tooLong = "a".repeat(65);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestCorrelationFilter.HEADER, tooLong);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain(new AtomicReference<>()));

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).hasSize(12).isNotEqualTo(tooLong);
    }

    @Test
    @DisplayName("en-tête entouré d'espaces → repris une fois élagué")
    void surroundingWhitespace_isTrimmed() {
        assertThat(RequestCorrelationFilter.resolveRequestId("  abc-123  ")).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("MDC nettoyé une fois la chaîne terminée")
    void mdc_isClearedAfterTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestCorrelationFilter.HEADER, "req-clean");

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(new AtomicReference<>()));

        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("MDC nettoyé même quand la chaîne lève une exception")
    void mdc_isClearedWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestCorrelationFilter.HEADER, "req-boom");
        FilterChain failingChain = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failingChain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("les autres entrées du MDC survivent au filtre")
    void otherMdcEntries_areLeftUntouched() throws Exception {
        MDC.put("other", "kept");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(new AtomicReference<>()));

        assertThat(MDC.get("other")).isEqualTo("kept");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    /** Chaîne qui relève ce que le MDC contient pendant le traitement de la requête. */
    private static FilterChain capturingChain(AtomicReference<String> seen) {
        return (req, res) -> seen.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
    }
}
