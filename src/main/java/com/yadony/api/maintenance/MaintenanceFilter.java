package com.yadony.api.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.common.i18n.MessagesResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pendant la maintenance, répond 503 {@code maintenance} à toute requête de l'application
 * mobile et du portail pro. L'application reconnaît ce code et n'affiche plus que son
 * écran de maintenance ; une version installée plus ancienne affiche « service
 * indisponible », bloquée elle aussi.
 *
 * <p>Placé dans la chaîne de sécurité JUSTE APRÈS {@code FirebaseTokenFilter} : l'appelant
 * est déjà identifié, ce qui laisse passer les administrateurs et les comptes exemptés.
 * Son enregistrement servlet automatique est désactivé ({@link MaintenanceFilterConfig}) : sinon
 * Spring Boot l'exécuterait aussi avant la sécurité, sans connaître l'appelant, et
 * {@code OncePerRequestFilter} sauterait ensuite le passage dans la chaîne.
 *
 * <p>Restent toujours ouverts : le back-office, la configuration publique (dont
 * {@code /config/maintenance}), la supervision, les webhooks et retours des prestataires
 * (un paiement en cours doit pouvoir se confirmer), les appels internes et les pages
 * web publiques en lecture seule.
 */
public class MaintenanceFilter extends OncePerRequestFilter {

    public static final String PROBLEM_CODE = "maintenance";
    static final String RETRY_AFTER_SECONDS = "120";
    private static final Logger log = LoggerFactory.getLogger(MaintenanceFilter.class);

    private static final List<String> OPEN_PREFIXES = List.of(
            "/admin/",
            "/config/",
            "/actuator/",
            "/internal/",
            "/kyc/webhook",
            "/payments/webhook",
            "/payments/stripe/webhook",
            "/payments/onboarding/",
            "/billing/webhook",
            "/pawapay/callbacks/",
            "/pawapay/return/",
            "/calls/webhook",
            "/public/",
            "/annonce/",
            "/demande/",
            "/tracking/public/",
            "/v3/api-docs",
            "/swagger-ui");

    /** Page publique du QR collé sur le colis : un seul segment, comme dans SecurityConfig. */
    private static final Pattern PUBLIC_SCAN = Pattern.compile("^/tracking/[^/]+/scan$");

    private final MaintenanceCache cache;
    private final ObjectMapper objectMapper;
    private final MessagesResolver messagesResolver;

    public MaintenanceFilter(MaintenanceCache cache, ObjectMapper objectMapper, MessagesResolver messagesResolver) {
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.messagesResolver = messagesResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        MaintenanceSnapshot snapshot = currentSnapshot();
        if (!snapshot.enabled() || isAlwaysOpen(request) || isAllowedCaller(snapshot)) {
            filterChain.doFilter(request, response);
            return;
        }
        writeMaintenance(response, snapshot);
    }

    /** Lecture fail-open : une base injoignable ne doit pas fermer l'API par erreur. */
    private MaintenanceSnapshot currentSnapshot() {
        try {
            return cache.snapshot();
        } catch (RuntimeException e) {
            log.warn("Maintenance state unavailable, request allowed: {}", e.getMessage());
            return MaintenanceSnapshot.OFF;
        }
    }

    static boolean isAlwaysOpen(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = pathWithoutContext(request);
        for (String prefix : OPEN_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return PUBLIC_SCAN.matcher(path).matches();
    }

    private static boolean isAllowedCaller(MaintenanceSnapshot snapshot) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        Object principal = auth.getPrincipal();
        if (principal instanceof AdminPrincipal) return true;
        return principal instanceof String uid && snapshot.isExempt(uid);
    }

    /** {@code getRequestURI()} inclut le context-path (/api/v1) : on le retire avant de comparer. */
    private static String pathWithoutContext(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        return path.startsWith(contextPath) ? path.substring(contextPath.length()) : path;
    }

    private void writeMaintenance(HttpServletResponse response, MaintenanceSnapshot snapshot) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                snapshot.message() != null ? snapshot.message()
                        : messagesResolver.forRequest().get("problem.maintenance.detail"));
        problem.setType(URI.create("https://yadony.app/errors/maintenance"));
        problem.setTitle("Maintenance");
        problem.setProperty("code", PROBLEM_CODE);
        problem.setProperty("estimatedEndAt",
                snapshot.estimatedEndAt() == null ? null : snapshot.estimatedEndAt().toString());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
