package com.yadony.api.common;

import io.sentry.Sentry;
import io.sentry.ScopeCallback;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void doFilter_usesIncomingSafeRequestIdAndClearsMdc() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestCorrelationFilter.HEADER, "req_123.ok");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.configureScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            filter.doFilter(request, response, new MockFilterChain());
        }

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("req_123.ok");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void doFilter_replacesUnsafeRequestId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestCorrelationFilter.HEADER, "bad header with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.configureScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            filter.doFilter(request, response, new MockFilterChain());
        }

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER))
                .isNotBlank()
                .isNotEqualTo("bad header with spaces");
    }
}
