package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Quotas journaliers de l'assistant IA, réglables depuis le panel admin. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsAssistantQuotaKeysTest {

    @Mock PlatformSettingRepository repository;
    @Mock PlatformSettingsCache cache;
    @Mock AuditService auditService;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) YadonyConfigProperties config;

    PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        service = new PlatformSettingsService(repository, cache, auditService, config,
                false, false, "", false);
    }

    @Test
    void defauts_quandLesLignesManquent() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.assistantDailyQuota()).isEqualTo(20);
        assertThat(service.assistantFormDraftDailyQuota()).isEqualTo(30);
    }

    @Test
    void litLesValeursEnregistrees() {
        when(cache.all()).thenReturn(Map.of(
                "assistant_daily_quota", "50",
                "assistant_form_draft_daily_quota", "75"));
        assertThat(service.assistantDailyQuota()).isEqualTo(50);
        assertThat(service.assistantFormDraftDailyQuota()).isEqualTo(75);
    }

    @Test
    void valeurIllisibleEnBase_retombeSurLeDefaut() {
        when(cache.all()).thenReturn(Map.of("assistant_daily_quota", "beaucoup"));
        assertThat(service.assistantDailyQuota()).isEqualTo(20);
    }

    @ParameterizedTest
    @CsvSource({
            "assistant_daily_quota, 0",
            "assistant_daily_quota, 1001",
            "assistant_daily_quota, abc",
            "assistant_form_draft_daily_quota, 0",
            "assistant_form_draft_daily_quota, 1001",
            "assistant_form_draft_daily_quota, 2.5"
    })
    void refuseHorsBornes_etNEcritRien(String key, String value) {
        assertThatThrownBy(() -> service.updateOne(PlatformSettingKey.fromKey(key), value, UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @CsvSource({"assistant_daily_quota, 1", "assistant_form_draft_daily_quota, 1000"})
    void accepteLesBornes_etAudite(String key, String value) {
        PlatformSettingEntity row = new PlatformSettingEntity(key, "20", PlatformSettingType.INTEGER);
        when(repository.findBySettingKey(key)).thenReturn(Optional.of(row));
        when(repository.findAll()).thenReturn(List.of(row));
        when(cache.all()).thenReturn(Map.of());

        service.updateOne(PlatformSettingKey.fromKey(key), " " + value + " ", UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo(value);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), any(), any());
    }

    @Test
    void lesDeuxClesSontDesEntiers() {
        assertThat(PlatformSettingKey.fromKey("assistant_daily_quota").type()).isEqualTo(PlatformSettingType.INTEGER);
        assertThat(PlatformSettingKey.fromKey("assistant_form_draft_daily_quota").type())
                .isEqualTo(PlatformSettingType.INTEGER);
    }
}
