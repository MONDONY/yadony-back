package com.yadony.api.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.i18n.MessagesResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaintenanceFilterTest {

    private final MaintenanceCache cache = mock(MaintenanceCache.class);
    private final MaintenanceFilter filter =
            new MaintenanceFilter(cache, new ObjectMapper(), mock(MessagesResolver.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/admin/users", "/api/v1/config/maintenance", "/api/v1/actuator/health",
            "/api/v1/internal/messaging/notify", "/api/v1/kyc/webhook/didit", "/api/v1/payments/stripe/webhook",
            "/api/v1/payments/webhook", "/api/v1/payments/onboarding/return", "/api/v1/billing/webhook",
            "/api/v1/pawapay/callbacks/deposits", "/api/v1/pawapay/return/abc", "/api/v1/calls/webhook",
            "/api/v1/public/annonce/1", "/api/v1/annonce/1", "/api/v1/demande/1",
            "/api/v1/tracking/public/abc", "/api/v1/tracking/0f5e/scan"})
    void cheminsToujoursOuverts(String uri) {
        assertThat(MaintenanceFilter.isAlwaysOpen(request("GET", uri))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/announcements", "/api/v1/auth/me", "/api/v1/tracking/0f5e/qr-code",
            "/api/v1/bids/me", "/api/v1/pro/dashboard"})
    void cheminsBloques(String uri) {
        assertThat(MaintenanceFilter.isAlwaysOpen(request("GET", uri))).isFalse();
    }

    @Test
    void preflightCorsToujoursOuvert() {
        assertThat(MaintenanceFilter.isAlwaysOpen(request("OPTIONS", "/api/v1/announcements"))).isTrue();
    }

    @Test
    void etatIllisible_laissePasser() throws Exception {
        when(cache.snapshot()).thenThrow(new IllegalStateException("db down"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/announcements"), response, chain);
        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void maintenance_bloqueEn503() throws Exception {
        when(cache.snapshot()).thenReturn(new MaintenanceSnapshot(true, "Pause", null, null, Set.of()));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/announcements"), response, chain);
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"maintenance\"").contains("Pause");
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/api/v1");
        return request;
    }
}
