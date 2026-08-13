package com.yadony.api.common;

import io.sentry.Sentry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final long SLOW_REQUEST_MS = 1_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        long startedAt = System.nanoTime();
        MDC.put("requestId", requestId);
        MDC.put("httpMethod", request.getMethod());
        MDC.put("httpPath", request.getRequestURI());
        response.setHeader(HEADER, requestId);
        Sentry.configureScope(scope -> scope.setTag("request_id", requestId));

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            int status = response.getStatus();
            if (status >= 500 || durationMs >= SLOW_REQUEST_MS) {
                log.warn("HTTP request completed method={} path={} status={} durationMs={} requestId={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs, requestId);
            }
            Sentry.configureScope(scope -> scope.removeTag("request_id"));
            MDC.clear();
        }
    }

    private static String resolveRequestId(String incoming) {
        if (incoming == null || incoming.isBlank() || incoming.length() > 80) {
            return UUID.randomUUID().toString();
        }
        String trimmed = incoming.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return UUID.randomUUID().toString();
            }
        }
        return trimmed;
    }
}
