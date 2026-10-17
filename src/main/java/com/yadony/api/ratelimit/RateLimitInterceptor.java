package com.yadony.api.ratelimit;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.common.i18n.MessagesResolver;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Applique les {@link RateLimitRule} avant le contrôleur. Placé dans Spring MVC et non
 * en filtre : l'authentification est déjà résolue (uid disponible), et le refus passe
 * par {@code GlobalExceptionHandler} comme toute autre erreur RFC 7807.
 *
 * <p>L'IP est {@code request.getRemoteAddr()} : en prod, nginx écrase
 * {@code X-Forwarded-For} avec l'adresse réelle du client et le
 * {@code ForwardedHeaderFilter} de Spring la reporte ici. Lire l'en-tête brut
 * laisserait le client choisir son IP.
 */
class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final RateLimitProperties properties;
    private final SlidingWindowRateLimiter limiter;
    private final MeterRegistry meterRegistry;
    private final MessagesResolver messagesResolver;
    private final Map<RateLimitRule, List<PathPattern>> patterns = new EnumMap<>(RateLimitRule.class);

    RateLimitInterceptor(RateLimitProperties properties, SlidingWindowRateLimiter limiter,
                         MeterRegistry meterRegistry, MessagesResolver messagesResolver) {
        this.properties = properties;
        this.limiter = limiter;
        this.meterRegistry = meterRegistry;
        this.messagesResolver = messagesResolver;
        PathPatternParser parser = new PathPatternParser();
        for (RateLimitRule rule : RateLimitRule.values()) {
            patterns.put(rule, rule.patterns().stream().map(parser::parse).toList());
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(PATH_HELPER.getPathWithinApplication(request));
        String method = request.getMethod();
        for (RateLimitRule rule : RateLimitRule.values()) {
            if (!applies(rule, method, path)) {
                continue;
            }
            String subject = subjectFor(rule, request);
            if (subject == null) {
                continue;
            }
            OptionalLong retryAfter = limiter.tryAcquire(
                    rule.name() + ':' + subject, properties.limitOf(rule), properties.windowOf(rule));
            if (retryAfter.isPresent()) {
                meterRegistry.counter("yadony.rate_limit.rejected", "rule", rule.propertyName()).increment();
                // Ni uid ni IP dans le log (Loki) : la règle et le chemin suffisent à repérer un abus.
                log.warn("Rate limit {} atteint sur {} {}", rule.propertyName(), method, path.value());
                throw new RateLimitExceededException(retryAfter.getAsLong(),
                        messagesResolver.forRequest().get("problem.too-many-requests.detail"));
            }
        }
        return true;
    }

    private boolean applies(RateLimitRule rule, String method, PathContainer path) {
        if (!rule.methods().isEmpty() && !rule.methods().contains(method)) {
            return false;
        }
        for (PathPattern pattern : patterns.get(rule)) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Identité comptée pour la règle, ou {@code null} si elle ne s'applique pas à ce
     * demandeur. Les administrateurs échappent aux règles par compte : le back-office
     * enchaîne des dizaines d'appels par écran et sa surface est déjà réservée.
     */
    private static String subjectFor(RateLimitRule rule, HttpServletRequest request) {
        if (rule.key() == RateLimitRule.Key.IP) {
            return request.getRemoteAddr();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof AdminPrincipal) {
            return null;
        }
        return principal instanceof String uid && !uid.isBlank() ? uid : null;
    }
}
