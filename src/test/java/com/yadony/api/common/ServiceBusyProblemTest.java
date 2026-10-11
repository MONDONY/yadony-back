package com.yadony.api.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentry.ScopeCallback;
import io.sentry.Sentry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@DisplayName("ServiceBusyProblem — 503 d'une connexion JDBC indisponible : saturation ou panne")
class ServiceBusyProblemTest {

    private static final String SATURATED =
            "HikariPool-1 - Connection is not available, request timed out after 5000ms "
                    + "(total=20, active=20, idle=0, waiting=151)";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meters);
        ServiceBusyProblem.resetReportWindow();
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meters);
        meters.close();
        ServiceBusyProblem.resetReportWindow();
    }

    // ── Classification, avec un vrai pool Hikari ─────────────────────────────

    @Test
    @DisplayName("vrai Hikari, toutes les connexions prêtées → POOL_SATURATED")
    void realHikariSaturationIsPoolSaturated() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:service_busy_" + System.nanoTime());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(250);
        try (HikariDataSource ds = new HikariDataSource(config);
             Connection held = ds.getConnection()) {
            Throwable timeout = catchThrowable(ds::getConnection);

            assertThat(timeout).isInstanceOf(SQLTransientConnectionException.class);
            assertThat(ServiceBusyProblem.classify(timeout)).isEqualTo(ServiceBusyProblem.Cause.POOL_SATURATED);
            assertThat(ServiceBusyProblem.classify(
                    new DataAccessResourceFailureException("Unable to acquire JDBC Connection", timeout)))
                    .isEqualTo(ServiceBusyProblem.Cause.POOL_SATURATED);
            assertThat(held.isValid(1)).isTrue();
        }
    }

    @Test
    @DisplayName("vrai Hikari, Postgres injoignable (connexion refusée) → DATABASE_UNREACHABLE")
    void realHikariUnreachableDatabaseIsDatabaseUnreachable() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/absent?connectTimeout=1");
        config.setUsername("nobody");
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(500);
        config.setInitializationFailTimeout(-1);
        try (HikariDataSource ds = new HikariDataSource(config)) {
            Throwable timeout = catchThrowable(ds::getConnection);

            assertThat(timeout).isInstanceOf(SQLTransientConnectionException.class);
            assertThat(ServiceBusyProblem.classify(timeout))
                    .isEqualTo(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE);
            assertThat(ServiceBusyProblem.isConnectionPoolTimeout(timeout)).isTrue();
        }
    }

    @Test
    @DisplayName("délai Hikari avec des connexions libres, cause jointe ou message inconnu → DATABASE_UNREACHABLE")
    void timeoutWithFreeConnectionsOrUnknownMessageIsUnreachable() {
        assertThat(ServiceBusyProblem.classify(new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 5000ms "
                        + "(total=0, active=0, idle=0, waiting=3)")))
                .isEqualTo(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE);
        assertThat(ServiceBusyProblem.classify(new SQLTransientConnectionException("validation failed")))
                .isEqualTo(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE);
        assertThat(ServiceBusyProblem.classify(new SQLTransientConnectionException((String) null)))
                .isEqualTo(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE);
        assertThat(ServiceBusyProblem.classify(new SQLTransientConnectionException(SATURATED,
                new SQLException("Connection refused"))))
                .isEqualTo(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE);
    }

    @Test
    @DisplayName("délai Hikari, pool entièrement prêté, sans cause → POOL_SATURATED (même sans décompte)")
    void saturatedMessageIsPoolSaturated() {
        assertThat(ServiceBusyProblem.classify(new RuntimeException("wrap",
                new SQLTransientConnectionException(SATURATED))))
                .isEqualTo(ServiceBusyProblem.Cause.POOL_SATURATED);
        assertThat(ServiceBusyProblem.classify(new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 5000ms")))
                .isEqualTo(ServiceBusyProblem.Cause.POOL_SATURATED);
    }

    @Test
    @DisplayName("toute autre panne de base n'est pas une indisponibilité de connexion")
    void otherFailuresAreNone() {
        assertThat(ServiceBusyProblem.classify(
                new DataAccessResourceFailureException("down", new SQLException("boom"))))
                .isEqualTo(ServiceBusyProblem.Cause.NONE);
        assertThat(ServiceBusyProblem.classify(null)).isEqualTo(ServiceBusyProblem.Cause.NONE);
        assertThat(ServiceBusyProblem.isConnectionPoolTimeout(new IllegalStateException())).isFalse();
    }

    @Test
    @DisplayName("une cause qui se référence elle-même ne boucle pas")
    void selfReferencingCauseTerminates() {
        RuntimeException selfCaused = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(ServiceBusyProblem.classify(selfCaused)).isEqualTo(ServiceBusyProblem.Cause.NONE);
    }

    // ── Signalement ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("saturation : compteur, aucun événement Sentry")
    void saturationCountsWithoutSentry() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ServiceBusyProblem.record(new SQLTransientConnectionException(SATURATED), "req-1", "controller");
            ServiceBusyProblem.record(new SQLTransientConnectionException(SATURATED), "req-2", "controller");

            sentry.verify(() -> Sentry.withScope(any(ScopeCallback.class)), never());
        }
        assertThat(counter("pool_saturated", "controller")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("base injoignable : compteur, Sentry ERROR une seule fois par fenêtre d'une minute")
    void unreachableReportsToSentryOncePerWindow() {
        Throwable down = new SQLTransientConnectionException(SATURATED, new SQLException("Connection refused"));
        long t0 = 1_000_000L;
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ServiceBusyProblem.record(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE, down, "req-1", "auth-filter", t0);
            ServiceBusyProblem.record(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE, down, null, "auth-filter",
                    t0 + ServiceBusyProblem.REPORT_WINDOW_MS - 1);
            sentry.verify(() -> Sentry.withScope(any(ScopeCallback.class)), times(1));

            ServiceBusyProblem.record(ServiceBusyProblem.Cause.DATABASE_UNREACHABLE, down, null, "auth-filter",
                    t0 + ServiceBusyProblem.REPORT_WINDOW_MS);
            sentry.verify(() -> Sentry.withScope(any(ScopeCallback.class)), times(2));
        }
        assertThat(counter("database_unreachable", "auth-filter")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("base injoignable : l'événement Sentry est capturé en ERROR avec le requestId")
    void unreachableEventCarriesErrorLevelAndRequestId() {
        Throwable down = new SQLTransientConnectionException("validation failed");
        io.sentry.IScope scope = org.mockito.Mockito.mock(io.sentry.IScope.class);
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            sentry.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> {
                inv.<ScopeCallback>getArgument(0).run(scope);
                return null;
            });

            ServiceBusyProblem.record(down, "req-9", "controller");

            sentry.verify(() -> Sentry.captureException(down));
        }
        org.mockito.Mockito.verify(scope).setLevel(io.sentry.SentryLevel.ERROR);
        org.mockito.Mockito.verify(scope).setTag("request_id", "req-9");
    }

    @Test
    @DisplayName("NONE : ni compteur ni Sentry")
    void noneRecordsNothing() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ServiceBusyProblem.record(new IllegalStateException("bug"), null, "controller");
            sentry.verify(() -> Sentry.withScope(any(ScopeCallback.class)), never());
        }
        // Le registre global recopie les compteurs déjà créés par d'autres tests : aucun ne bouge.
        assertThat(meters.find(ServiceBusyProblem.METRIC).counters()).allMatch(c -> c.count() == 0);
    }

    @Test
    @DisplayName("fenêtre de signalement : première fois ouverte, puis fermée une minute")
    void reportWindow() {
        assertThat(ServiceBusyProblem.shouldReport(0)).isTrue();
        assertThat(ServiceBusyProblem.shouldReport(ServiceBusyProblem.REPORT_WINDOW_MS - 1)).isFalse();
        assertThat(ServiceBusyProblem.shouldReport(ServiceBusyProblem.REPORT_WINDOW_MS)).isTrue();
    }

    @Test
    @DisplayName("corps RFC 7807 : 503, type, code, requestId facultatif")
    void buildsProblemDetail() {
        ProblemDetail withId = ServiceBusyProblem.of("surchargé", "req-1");
        ProblemDetail withoutId = ServiceBusyProblem.of("surchargé", null);

        assertThat(withId.getStatus()).isEqualTo(503);
        assertThat(withId.getType().toString()).isEqualTo("https://yadony.app/errors/service-busy");
        assertThat(withId.getTitle()).isEqualTo("Service Busy");
        assertThat(withId.getDetail()).isEqualTo("surchargé");
        assertThat(withId.getProperties()).containsEntry("code", "service-busy").containsEntry("requestId", "req-1");
        assertThat(withoutId.getProperties()).doesNotContainKey("requestId");
    }

    private double counter(String cause, String where) {
        return meters.get(ServiceBusyProblem.METRIC).tag("cause", cause).tag("where", where).counter().count();
    }
}
