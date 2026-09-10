package com.yadony.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Corrèle chaque requête HTTP à un identifiant unique.
 *
 * <p>L'identifiant est repris de l'en-tête {@value #HEADER} quand le client en fournit un
 * (l'app peut ainsi citer le sien dans un ticket support), sinon généré ici. Il est posé
 * dans le MDC sous {@value #MDC_KEY} pour apparaître sur chaque ligne de log de la requête
 * (cf. {@code logging.pattern.correlation} dans application.yml), renvoyé au client dans
 * l'en-tête de réponse du même nom, puis retiré du MDC quoi qu'il arrive : les threads du
 * pool servlet sont réutilisés, un identifiant qui traîne serait attribué à la requête
 * suivante.
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} : le filtre doit envelopper la chaîne Spring
 * Security pour que les refus 401/403 et les logs de {@code FirebaseTokenFilter} soient
 * eux aussi corrélés.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /**
     * Un identifiant fourni par le client n'est repris que s'il est sobre : ni espace ni
     * caractère de contrôle (qui casserait une ligne de log ou l'en-tête de réponse),
     * longueur bornée. Tout le reste est remplacé par un identifiant généré.
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Longueur de l'identifiant généré : 12 hexadécimaux (48 bits), assez pour retrouver
     *  une requête dans les logs sans alourdir chaque ligne d'un UUID complet. */
    private static final int GENERATED_LENGTH = 12;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Reprend l'identifiant entrant s'il est sûr, sinon en génère un. */
    static String resolveRequestId(String incoming) {
        if (incoming != null) {
            String trimmed = incoming.trim();
            if (SAFE_REQUEST_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return generateRequestId();
    }

    static String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, GENERATED_LENGTH);
    }
}
