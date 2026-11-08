package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** Numéros différents par compte et par 24 h avant suspension automatique (08/11/2026). */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsSmsOtpMaxNumbersKeyTest {

    private static final String KEY = "sms_otp_max_numbers_per_account";

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
    void ligneAbsente_defautTrois() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.smsOtpMaxNumbersPerAccount()).isEqualTo(3);
    }

    @Test
    void valeurEnBase_lue() {
        when(cache.all()).thenReturn(Map.of(KEY, "5"));
        assertThat(service.smsOtpMaxNumbersPerAccount()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1", "0", "21"})
    void valeurIllisibleOuHorsBornesEnBase_defautTrois(String raw) {
        when(cache.all()).thenReturn(Map.of(KEY, raw));
        assertThat(service.smsOtpMaxNumbersPerAccount()).isEqualTo(3);
    }

    @Test
    void modification_normaliseAuditeEtVideLeCache() {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, "3", PlatformSettingType.INTEGER);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.SMS_OTP_MAX_NUMBERS_PER_ACCOUNT, " 5 "), admin);

        assertThat(row.getSettingValue()).isEqualTo("5");
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin), any());
        verify(cache).evict();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "21", "trois", "2.5"})
    void horsBornes_refuse422(String value) {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SMS_OTP_MAX_NUMBERS_PER_ACCOUNT, value), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("entre 2 et 20");
        verifyNoInteractions(auditService);
    }

    @Test
    void ligneManquante_vueAdminServieAvecLaValeurEffective() {
        when(cache.all()).thenReturn(Map.of());
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.listByKey())
                .filteredOn(v -> v.key() == PlatformSettingKey.SMS_OTP_MAX_NUMBERS_PER_ACCOUNT)
                .singleElement()
                .satisfies(v -> assertThat(v.value()).isEqualTo("3"));
    }
}
