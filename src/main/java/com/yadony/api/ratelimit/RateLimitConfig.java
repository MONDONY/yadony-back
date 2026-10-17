package com.yadony.api.ratelimit;

import com.yadony.api.common.i18n.MessagesResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * Branche {@link RateLimitInterceptor} sur toute l'API, sauf les webhooks et callbacks
 * des prestataires (Stripe, Didit, pawaPay, Sentry, Stream) : leur signature les
 * authentifie, ils arrivent de quelques IP, et un 429 y ferait perdre un événement
 * (Didit ne rejoue pas un 429).
 */
@Configuration
class RateLimitConfig implements WebMvcConfigurer {

    static final String[] EXCLUDED = {
            "/payments/webhook",
            "/kyc/webhook",
            "/kyc/webhook/**",
            "/billing/webhook",
            "/pawapay/callbacks/**",
            "/pawapay/return/**",
            "/admin/sentry-webhook",
            "/calls/webhook",
            "/internal/**",
            "/actuator/**",
            // Redispatch d'erreur de Boot : la requête d'origine a déjà été comptée.
            "/error"};

    private final RateLimitInterceptor interceptor;

    RateLimitConfig(RateLimitProperties properties, MeterRegistry meterRegistry,
                    MessagesResolver messagesResolver) {
        Duration longest = Arrays.stream(RateLimitRule.values())
                .map(properties::windowOf)
                .max(Duration::compareTo)
                .orElse(Duration.ofHours(1));
        this.interceptor = new RateLimitInterceptor(properties,
                new SlidingWindowRateLimiter(Clock.systemUTC(), longest),
                meterRegistry, messagesResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**").excludePathPatterns(EXCLUDED);
    }
}
