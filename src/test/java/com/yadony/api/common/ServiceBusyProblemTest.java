package com.yadony.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ServiceBusyProblem — 503 d'un pool JDBC épuisé")
class ServiceBusyProblemTest {

    @Test
    @DisplayName("délai d'attente Hikari trouvé dans la chaîne des causes")
    void detectsPoolTimeoutInCauseChain() {
        Exception wrapped = new DataAccessResourceFailureException("Unable to acquire JDBC Connection",
                new RuntimeException("JDBCConnectionException",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));

        assertThat(ServiceBusyProblem.isConnectionPoolTimeout(wrapped)).isTrue();
    }

    @Test
    @DisplayName("toute autre panne de base n'est pas une surcharge")
    void otherFailuresAreNotPoolTimeouts() {
        assertThat(ServiceBusyProblem.isConnectionPoolTimeout(
                new DataAccessResourceFailureException("down", new SQLException("connection refused")))).isFalse();
        assertThat(ServiceBusyProblem.isConnectionPoolTimeout(null)).isFalse();
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

        assertThat(ServiceBusyProblem.isConnectionPoolTimeout(selfCaused)).isFalse();
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
}
