package com.yadony.api.common;

import io.micrometer.core.instrument.Metrics;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Réponse RFC 7807 quand Hikari n'a pas fourni de connexion JDBC dans son
 * {@code connection-timeout} : 503 {@code service-busy} avec {@code Retry-After}.
 *
 * <p>Deux causes très différentes lèvent la même {@link SQLTransientConnectionException} :
 * <ul>
 *   <li>{@link Cause#POOL_SATURATED} : toutes les connexions sont prêtées
 *       ({@code active == total}) et Postgres répond. C'est une surcharge passagère,
 *       pas un bug : WARN et compteur, aucun événement Sentry.</li>
 *   <li>{@link Cause#DATABASE_UNREACHABLE} : Hikari n'arrive plus à ouvrir de
 *       connexion (Postgres arrêté, connexion refusée, validation en échec). Il joint
 *       alors la dernière erreur de connexion comme cause, et des connexions restent
 *       libres dans le décompte. C'est une vraie panne : même 503 côté client, mais
 *       un événement Sentry ERROR, au plus un par {@link #REPORT_WINDOW_MS} pour ne
 *       pas déclencher une avalanche d'événements pendant la panne.</li>
 * </ul>
 *
 * <p>Partagé entre {@link GlobalExceptionHandler} (contrôleurs) et le filtre
 * d'authentification, qui s'exécute AVANT le {@code DispatcherServlet} et n'est donc
 * pas couvert par le {@code @RestControllerAdvice} : l'épuisement du pool pendant la
 * résolution du compte remontait jusqu'à Tomcat en 500 et chaque requête créait un
 * événement Sentry (YADONY-BACK-STAGING-W, tests de charge du 07 et du 08/10).
 */
public final class ServiceBusyProblem {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusyProblem.class);

    /** Secondes conseillées au client avant de réessayer. */
    public static final String RETRY_AFTER_SECONDS = "2";

    public static final String CODE = "service-busy";

    /** Compteur Micrometer : {@code yadony_db_connection_unavailable_total{cause,where}}. */
    public static final String METRIC = "yadony.db.connection.unavailable";

    /** Un événement Sentry au plus par minute pendant une panne de la base. */
    static final long REPORT_WINDOW_MS = 60_000;

    /** Message de Hikari quand le délai d'emprunt expire (HikariPool#createTimeoutException). */
    private static final String HIKARI_TIMEOUT = "Connection is not available, request timed out";

    private static final Pattern POOL_STATS = Pattern.compile("total=(\\d+), active=(\\d+)");

    private static final AtomicLong lastReportAt = new AtomicLong(Long.MIN_VALUE);

    public enum Cause {
        /** Pas une indisponibilité de connexion. */
        NONE,
        /** Toutes les connexions sont prêtées, Postgres répond. */
        POOL_SATURATED,
        /** Hikari n'arrive plus à ouvrir de connexion : panne de la base ou du réseau. */
        DATABASE_UNREACHABLE
    }

    private ServiceBusyProblem() {
    }

    /** Vrai pour les deux causes : le client reçoit le même 503 {@code service-busy}. */
    public static boolean isConnectionPoolTimeout(Throwable ex) {
        return classify(ex) != Cause.NONE;
    }

    /**
     * Saturation : délai d'emprunt de Hikari sans erreur de connexion jointe, avec un
     * pool non vide entièrement prêté ({@code active == total > 0}) quand le message porte le
     * décompte. Tout autre {@link SQLTransientConnectionException} (cause jointe,
     * connexions libres, message inconnu) est traité comme une base injoignable.
     */
    public static Cause classify(Throwable ex) {
        SQLTransientConnectionException timeout = findTimeout(ex);
        if (timeout == null) {
            return Cause.NONE;
        }
        String message = timeout.getMessage() == null ? "" : timeout.getMessage();
        if (!message.contains(HIKARI_TIMEOUT) || timeout.getCause() != null) {
            return Cause.DATABASE_UNREACHABLE;
        }
        Matcher stats = POOL_STATS.matcher(message);
        // Pool vide (total=0) : Hikari n'a rien pu ouvrir, ce n'est pas une saturation.
        if (stats.find() && (stats.group(1).equals("0") || !stats.group(1).equals(stats.group(2)))) {
            return Cause.DATABASE_UNREACHABLE;
        }
        return Cause.POOL_SATURATED;
    }

    /**
     * Trace l'indisponibilité : WARN et compteur dans les deux cas, plus un événement
     * Sentry ERROR (au plus un par fenêtre) quand la base est injoignable.
     *
     * @param where point d'entrée, pour le log et le compteur ({@code controller}, {@code auth-filter})
     */
    public static void record(Throwable ex, String requestId, String where) {
        record(classify(ex), ex, requestId, where, System.currentTimeMillis());
    }

    static void record(Cause cause, Throwable ex, String requestId, String where, long nowMillis) {
        if (cause == Cause.NONE) {
            return;
        }
        Metrics.counter(METRIC, "cause", cause.name().toLowerCase(), "where", where).increment();
        if (cause == Cause.POOL_SATURATED) {
            log.warn("Connection pool exhausted ({}) requestId={}: {}", where, requestId, ex.getMessage());
            return;
        }
        log.warn("Database unreachable ({}) requestId={}: {}", where, requestId, ex.getMessage());
        if (shouldReport(nowMillis)) {
            Sentry.withScope(scope -> {
                scope.setLevel(SentryLevel.ERROR);
                scope.setTag("db_unavailable", "unreachable");
                if (requestId != null) {
                    scope.setTag("request_id", requestId);
                }
                Sentry.captureException(ex);
            });
        }
    }

    /** Vrai au plus une fois par fenêtre de {@link #REPORT_WINDOW_MS}, même en concurrence. */
    static boolean shouldReport(long nowMillis) {
        long last = lastReportAt.get();
        if (last != Long.MIN_VALUE && nowMillis - last < REPORT_WINDOW_MS) {
            return false;
        }
        return lastReportAt.compareAndSet(last, nowMillis);
    }

    /** Pour les tests : rouvre la fenêtre de signalement. */
    static void resetReportWindow() {
        lastReportAt.set(Long.MIN_VALUE);
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

    private static SQLTransientConnectionException findTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException timeout) {
                return timeout;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
