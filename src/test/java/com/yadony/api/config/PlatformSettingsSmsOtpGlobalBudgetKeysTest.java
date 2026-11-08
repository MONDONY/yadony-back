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

/** Plafond global des SMS de code (toute l'app, 1 h et 24 h), réglé dans le back-office (08/11/2026). */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsSmsOtpGlobalBudgetKeysTest {

    private static final String HOUR = "sms_otp_max_sends_per_hour_global";
    private static final String DAY = "sms_otp_max_sends_per_day_global";

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
    void lignesAbsentes_defauts30Et150() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.smsOtpMaxSendsPerHourGlobal()).isEqualTo(30);
        assertThat(service.smsOtpMaxSendsPerDayGlobal()).isEqualTo(150);
    }

    @Test
    void valeursEnBase_lues() {
        when(cache.all()).thenReturn(Map.of(HOUR, "10", DAY, "40"));
        assertThat(service.smsOtpMaxSendsPerHourGlobal()).isEqualTo(10);
        assertThat(service.smsOtpMaxSendsPerDayGlobal()).isEqualTo(40);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-5", "1001"})
    void heureIllisibleOuHorsBornes_defaut30(String raw) {
        when(cache.all()).thenReturn(Map.of(HOUR, raw));
        assertThat(service.smsOtpMaxSendsPerHourGlobal()).isEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "10001"})
    void jourIllisibleOuHorsBornes_defaut150(String raw) {
        when(cache.all()).thenReturn(Map.of(DAY, raw));
        assertThat(service.smsOtpMaxSendsPerDayGlobal()).isEqualTo(150);
    }

    @Test
    void modification_normaliseAuditeEtVideLeCache() {
        PlatformSettingEntity row = new PlatformSettingEntity(HOUR, "30", PlatformSettingType.INTEGER);
        when(repository.findBySettingKey(HOUR)).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL, " 10 "), admin);

        assertThat(row.getSettingValue()).isEqualTo("10");
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin), any());
        verify(cache).evict();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "dix"})
    void heureHorsBornes_refusee422(String value) {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL, value), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("entre 1 et 1000");
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10001"})
    void jourHorsBornes_refuse422(String value) {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SMS_OTP_MAX_SENDS_PER_DAY_GLOBAL, value), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("entre 1 et 10000");
        verifyNoInteractions(auditService);
    }

    @Test
    void lignesManquantes_vueAdminServieAvecLesValeursEffectives() {
        when(cache.all()).thenReturn(Map.of());
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.listByKey())
                .filteredOn(v -> v.key() == PlatformSettingKey.SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL
                        || v.key() == PlatformSettingKey.SMS_OTP_MAX_SENDS_PER_DAY_GLOBAL)
                .extracting(PlatformSettingView::value)
                .containsExactly("30", "150");
    }
}
