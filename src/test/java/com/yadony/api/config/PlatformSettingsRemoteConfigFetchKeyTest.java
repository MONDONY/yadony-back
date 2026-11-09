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

/** Intervalle de relecture de Remote Config (minutes), réglable depuis le panel admin. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsRemoteConfigFetchKeyTest {

    private static final String KEY = "remote_config_fetch_minutes";

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
    void defaut60_quandLaLigneManque() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.remoteConfigFetchMinutes()).isEqualTo(60);
    }

    @Test
    void litLaValeurEnregistree() {
        when(cache.all()).thenReturn(Map.of(KEY, "15"));
        assertThat(service.remoteConfigFetchMinutes()).isEqualTo(15);
    }

    @ParameterizedTest
    @CsvSource({"beaucoup", "4", "1441", "0", "-3"})
    void valeurIllisibleOuHorsBornesEnBase_retombeSurLeDefaut(String raw) {
        when(cache.all()).thenReturn(Map.of(KEY, raw));
        assertThat(service.remoteConfigFetchMinutes()).isEqualTo(60);
    }

    @ParameterizedTest
    @CsvSource({"4", "1441", "0", "abc", "2.5"})
    void refuseHorsBornes_etNEcritRien(String value) {
        assertThatThrownBy(() -> service.updateOne(PlatformSettingKey.REMOTE_CONFIG_FETCH_MINUTES, value,
                UUID.randomUUID())).isInstanceOf(YadonyBusinessException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @CsvSource({"5", "1440"})
    void accepteLesBornes_etAudite(String value) {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, "60", PlatformSettingType.INTEGER);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));
        when(repository.findAll()).thenReturn(List.of(row));
        when(cache.all()).thenReturn(Map.of());

        service.updateOne(PlatformSettingKey.REMOTE_CONFIG_FETCH_MINUTES, " " + value + " ", UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo(value);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), any(), any());
    }

    @Test
    void cleDeTypeEntier() {
        assertThat(PlatformSettingKey.fromKey(KEY)).isEqualTo(PlatformSettingKey.REMOTE_CONFIG_FETCH_MINUTES);
        assertThat(PlatformSettingKey.REMOTE_CONFIG_FETCH_MINUTES.type()).isEqualTo(PlatformSettingType.INTEGER);
    }
}
