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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Assistant du centre d'aide de la console : interrupteur et quota réglés depuis le back-office. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsAdminHelpKeysTest {

    @Mock PlatformSettingRepository repository;
    @Mock PlatformSettingsCache cache;
    @Mock AuditService auditService;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) YadonyConfigProperties config;

    PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        service = new PlatformSettingsService(repository, cache, auditService, config, false, false, "", false);
    }

    @Test
    void lignesAbsentes_allumeEt60() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.adminHelpAssistantEnabled()).isTrue();
        assertThat(service.adminHelpDailyQuota()).isEqualTo(PlatformSettingKey.DEFAULT_ADMIN_HELP_DAILY_QUOTA);
    }

    @Test
    void valeursReglees() {
        when(cache.all()).thenReturn(Map.of("admin_help_assistant_enabled", "false", "admin_help_daily_quota", "15"));
        assertThat(service.adminHelpAssistantEnabled()).isFalse();
        assertThat(service.adminHelpDailyQuota()).isEqualTo(15);
    }

    @Test
    void typesDesCles() {
        assertThat(PlatformSettingKey.fromKey("admin_help_assistant_enabled").type()).isEqualTo(PlatformSettingType.BOOLEAN);
        assertThat(PlatformSettingKey.fromKey("admin_help_daily_quota").type()).isEqualTo(PlatformSettingType.INTEGER);
    }

    @ParameterizedTest
    @CsvSource({"admin_help_assistant_enabled, oui", "admin_help_daily_quota, 0", "admin_help_daily_quota, 1001",
            "admin_help_daily_quota, 2.5"})
    void valeursInvalidesRefusees(String key, String value) {
        assertThatThrownBy(() -> service.update(Map.of(PlatformSettingKey.fromKey(key), value), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class);
    }
}
