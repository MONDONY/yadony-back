package com.yadony.api.assistant;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private final AssistantQuotaGuard guard = new AssistantQuotaGuard(settings, clock);

    {
        when(settings.assistantDailyQuota()).thenReturn(3);
    }

    @Test
    void decompteEtRefuseAuDela() {
        assertThat(guard.consume("u1")).isEqualTo(2);
        assertThat(guard.consume("u1")).isEqualTo(1);
        assertThat(guard.consume("u1")).isZero();
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(429);
            assertThat(e.getErrorCode()).isEqualTo("assistant-quota-exceeded");
            assertThat(e.getProperties()).containsEntry("retryAfter", "2026-10-17T00:00:00Z");
        });
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

    /** Réglage admin changé en cours de journée : effet immédiat, le compteur du jour est gardé. */
    @Test
    void quotaModifieDepuisLAdminSansRedemarrage() {
        guard.consume("u1"); guard.consume("u1"); guard.consume("u1");
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOf(YadonyBusinessException.class);
        when(settings.assistantDailyQuota()).thenReturn(10);
        assertThat(guard.consume("u1")).isEqualTo(5);
        when(settings.assistantDailyQuota()).thenReturn(4);
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOf(YadonyBusinessException.class);
    }
}
