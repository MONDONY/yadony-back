package com.yadony.api.assistant.formdraft;

import com.yadony.api.assistant.AssistantQuotaGuard;
import com.yadony.api.assistant.quota.InMemoryAssistantUserDailyUsageStore;
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

class FormDraftQuotaGuardTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T22:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final FormDraftQuotaGuard guard = new FormDraftQuotaGuard(settings, clock);

    {
        when(settings.assistantFormDraftDailyQuota()).thenReturn(2);
        when(settings.assistantDailyQuota()).thenReturn(1);
    }

    @Test
    void decompteEtRefuseAuDela() {
        assertThat(guard.consume("u1")).isEqualTo(1);
        assertThat(guard.consume("u1")).isZero();
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(429);
            assertThat(e.getErrorCode()).isEqualTo("assistant-form-draft-quota-exceeded");
            assertThat(e.getProperties()).containsEntry("retryAfter", "2026-10-18T00:00:00Z");
        });
    }

    @Test
    void repartAZeroLeLendemainUtc() {
        guard.consume("u1");
        guard.consume("u1");
        now.set(Instant.parse("2026-10-18T00:00:01Z"));
        assertThat(guard.consume("u1")).isEqualTo(1);
    }

    @Test
    void separeDuQuotaDeLAssistant() {
        AssistantQuotaGuard assistant = new AssistantQuotaGuard(new InMemoryAssistantUserDailyUsageStore(), settings, clock);
        assistant.consume("u1");
        assertThat(guard.consume("u1")).isEqualTo(1);
    }

    /** Réglage admin changé en cours de journée : effet immédiat, le compteur du jour est gardé. */
    @Test
    void quotaModifieDepuisLAdminSansRedemarrage() {
        guard.consume("u1");
        guard.consume("u1");
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOf(YadonyBusinessException.class);
        when(settings.assistantFormDraftDailyQuota()).thenReturn(5);
        assertThat(guard.consume("u1")).isEqualTo(1);
    }
}
