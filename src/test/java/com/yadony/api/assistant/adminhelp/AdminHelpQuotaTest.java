package com.yadony.api.assistant.adminhelp;

import com.yadony.api.assistant.quota.AssistantUserDailyUsageStore;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminHelpQuotaTest {

    final AssistantUserDailyUsageStore store = mock(AssistantUserDailyUsageStore.class);
    final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);
    final AdminHelpQuota quota = new AdminHelpQuota(store, settings, clock);

    {
        when(settings.adminHelpDailyQuota()).thenReturn(10);
    }
    final UUID admin = UUID.randomUUID();

    @Test
    void decompteSousUneCleAdmin() {
        when(store.tryConsume(eq("admin:" + admin), eq(LocalDate.of(2026, 11, 16)), eq(10))).thenReturn(3);
        assertThat(quota.consume(admin)).isEqualTo(7);
    }

    @Test
    void refuseAuDelaDuQuota() {
        when(store.tryConsume(anyString(), any(), anyInt())).thenReturn(AssistantUserDailyUsageStore.REFUSED);
        assertThatThrownBy(() -> quota.consume(admin)).isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void baseInjoignableLaissePasser() {
        when(store.tryConsume(anyString(), any(), anyInt())).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(quota.consume(admin)).isEqualTo(10);
    }

    @Test
    void constructeurSpring() {
        assertThat(new AdminHelpQuota(store, settings)).isNotNull();
    }
}
