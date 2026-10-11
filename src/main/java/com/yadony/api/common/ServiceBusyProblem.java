package com.yadony.api.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.sql.SQLTransientConnectionException;

/**
 * Réponse RFC 7807 d'un pool de connexions JDBC épuisé : Hikari n'a pas fourni de
 * connexion dans son {@code connection-timeout}. C'est une surcharge passagère, pas un
 * bug : 503 {@code service-busy} avec {@code Retry-After}, un WARN et aucun événement
 * Sentry.
 *
 * <p>Partagé entre {@link GlobalExceptionHandler} (contrôleurs) et le filtre
 * d'authentification, qui s'exécute AVANT le {@code DispatcherServlet} et n'est donc
 * pas couvert par le {@code @RestControllerAdvice} : l'épuisement du pool pendant la
 * résolution du compte remontait jusqu'à Tomcat en 500 et chaque requête créait un
 * événement Sentry (YADONY-BACK-STAGING-W, tests de charge du 07 et du 08/10).
 */
public final class ServiceBusyProblem {

    /** Secondes conseillées au client avant de réessayer. */
    public static final String RETRY_AFTER_SECONDS = "2";

    public static final String CODE = "service-busy";

    private ServiceBusyProblem() {
    }

    /**
     * Vrai si la chaîne des causes contient le délai d'attente du pool
     * ({@link SQLTransientConnectionException}, levée par Hikari). Toute autre panne
     * d'accès à la base reste une vraie erreur.
     */
    public static boolean isConnectionPoolTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Corps {@code application/problem+json} du 503 ; {@code requestId} facultatif. */
    public static ProblemDetail of(String detail, String requestId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problem.setType(URI.create("https://yadony.app/errors/" + CODE));
        problem.setTitle("Service Busy");
        problem.setProperty("code", CODE);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
