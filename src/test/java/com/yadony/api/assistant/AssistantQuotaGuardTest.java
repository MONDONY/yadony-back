package com.yadony.api.assistant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yadony.api.assistant.quota.AssistantUserDailyUsageStore;
import com.yadony.api.assistant.quota.InMemoryAssistantUserDailyUsageStore;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantQuotaGuardTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T22:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final InMemoryAssistantUserDailyUsageStore store = new InMemoryAssistantUserDailyUsageStore();
    private final AssistantQuotaGuard guard = new AssistantQuotaGuard(store, settings, clock);
    private ListAppender<ILoggingEvent> logs;

    {
        when(settings.assistantDailyQuota()).thenReturn(3);
    }

    @AfterEach
    void detachLogs() {
        if (logs != null) ((Logger) LoggerFactory.getLogger(AssistantQuotaGuard.class)).detachAppender(logs);
    }

    private static void assertQuotaExceeded(Runnable call, String retryAfter) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(429);
            assertThat(e.getErrorCode()).isEqualTo("assistant-quota-exceeded");
            assertThat(e.getProperties()).containsEntry("retryAfter", retryAfter);
        });
    }

    @Test
    void decompteEtRefuseAuDela() {
        assertThat(guard.consume("u1")).isEqualTo(2);
        assertThat(guard.consume("u1")).isEqualTo(1);
        assertThat(guard.consume("u1")).isZero();
        assertQuotaExceeded(() -> guard.consume("u1"), "2026-10-17T00:00:00Z");
    }

    @Test
    void chaqueUtilisateurASonQuota() {
        guard.consume("u1"); guard.consume("u1"); guard.consume("u1");
        assertThat(guard.consume("u2")).isEqualTo(2);
    }

    @Test
    void repartLeLendemain() {
        guard.consume("u1"); guard.consume("u1"); guard.consume("u1");
        now.set(Instant.parse("2026-10-17T00:00:01Z"));
        assertThat(guard.consume("u1")).isEqualTo(2);
    }

    /** Le jour est le jour UTC, pas celui du serveur : 23:59:59 UTC compte encore pour la veille. */
    @Test
    void leJourEstLeJourUtc() {
        now.set(Instant.parse("2026-10-16T23:59:59Z"));
        guard.consume("u1");
        assertThat(store.questions("u1", LocalDate.of(2026, 10, 16))).isEqualTo(1);
        now.set(Instant.parse("2026-10-17T00:00:00Z"));
        guard.consume("u1");
        assertThat(store.questions("u1", LocalDate.of(2026, 10, 17))).isEqualTo(1);
    }

    /** Une question refusée n'est pas comptée : la base ne dépasse jamais le quota. */
    @Test
    void uneQuestionRefuseeNEstPasComptee() {
        guard.consume("u1"); guard.consume("u1"); guard.consume("u1");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> guard.consume("u1")).isInstanceOf(YadonyBusinessException.class);
        }
        assertThat(store.questions("u1", LocalDate.of(2026, 10, 16))).isEqualTo(3);
    }

    /** Réglage admin changé en cours de journée : effet immédiat, le compteur du jour est gardé. */
    @Test
    void quotaModifieDepuisLAdminSansRedemarrage() {
        guard.consume("u1"); guard.consume("u1"); guard.consume("u1");
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOf(YadonyBusinessException.class);
        when(settings.assistantDailyQuota()).thenReturn(10);
        assertThat(guard.consume("u1")).isEqualTo(6);
        when(settings.assistantDailyQuota()).thenReturn(4);
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOf(YadonyBusinessException.class);
    }

    /** Redémarrage du back : une nouvelle instance relit le compte en base, il ne repart pas à zéro. */
    @Test
    void unRedemarrageNeRemetPasLeCompteurAZero() {
        guard.consume("u1"); guard.consume("u1");
        AssistantQuotaGuard apresRedemarrage = new AssistantQuotaGuard(store, settings, clock);
        assertThat(apresRedemarrage.consume("u1")).isZero();
        assertQuotaExceeded(() -> apresRedemarrage.consume("u1"), "2026-10-17T00:00:00Z");
    }

    /** Base en panne : on laisse passer, mais dans la limite du quota, comptée en mémoire. */
    @Test
    void baseEnPanneLaissePasserDansLeQuotaEnMemoire() {
        AssistantUserDailyUsageStore enPanne = mock(AssistantUserDailyUsageStore.class);
        when(enPanne.tryConsume(anyString(), any(), anyInt())).thenThrow(new DataAccessResourceFailureException("down"));
        AssistantQuotaGuard g = new AssistantQuotaGuard(enPanne, settings, clock);

        assertThat(g.consume("u1")).isEqualTo(2);
        assertThat(g.consume("u1")).isEqualTo(1);
        assertThat(g.consume("u1")).isZero();
        assertQuotaExceeded(() -> g.consume("u1"), "2026-10-17T00:00:00Z");
        assertThat(g.consume("u2")).isEqualTo(2);

        now.set(Instant.parse("2026-10-17T00:00:01Z"));
        assertThat(g.consume("u1")).isEqualTo(2);
    }

    @Test
    void transactionImpossibleEstTraiteeCommeUneBaseEnPanne() {
        AssistantUserDailyUsageStore enPanne = mock(AssistantUserDailyUsageStore.class);
        when(enPanne.tryConsume(anyString(), any(), anyInt())).thenThrow(new CannotCreateTransactionException("pool"));
        AssistantQuotaGuard g = new AssistantQuotaGuard(enPanne, settings, clock);
        assertThat(g.consume("u1")).isEqualTo(2);
    }

    /** Une autre erreur (bug, contrainte) n'est pas un fail-open : elle remonte. */
    @Test
    void uneErreurQuiNEstPasUnePanneDeBaseRemonte() {
        AssistantUserDailyUsageStore casse = mock(AssistantUserDailyUsageStore.class);
        when(casse.tryConsume(anyString(), any(), anyInt())).thenThrow(new IllegalStateException("bug"));
        AssistantQuotaGuard g = new AssistantQuotaGuard(casse, settings, clock);
        assertThatThrownBy(() -> g.consume("u1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void baseEnPanneUnSeulWarnParMinute() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(AssistantQuotaGuard.class)).addAppender(logs);
        when(settings.assistantDailyQuota()).thenReturn(100);
        AssistantUserDailyUsageStore enPanne = mock(AssistantUserDailyUsageStore.class);
        when(enPanne.tryConsume(anyString(), any(), anyInt())).thenThrow(new DataAccessResourceFailureException("down"));
        AssistantQuotaGuard g = new AssistantQuotaGuard(enPanne, settings, clock);

        for (int i = 0; i < 10; i++) g.consume("u1");
        now.set(now.get().plusSeconds(59));
        g.consume("u1");
        assertThat(logs.list).hasSize(1);
        now.set(now.get().plusSeconds(1));
        g.consume("u1");
        assertThat(logs.list).hasSize(2);
        assertThat(logs.list).allMatch(e -> e.getLevel() == Level.WARN);
    }
}
