package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * Code SMS réservé aux numéros inscrits, piloté depuis le back-office (pompage de SMS du
 * 08/11/2026). Toute ambiguïté vaut « réservé » : chaque SMS est facturé.
 */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsSmsOtpRegisteredOnlyKeyTest {

    private static final String KEY = "sms_otp_registered_numbers_only";

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
    void ligneAbsente_reserveAuxInscrits() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.smsOtpRegisteredNumbersOnly()).isTrue();
    }

    @Test
    void falseExplicite_rouvreLInscriptionParSms() {
        when(cache.all()).thenReturn(Map.of(KEY, "false"));
        assertThat(service.smsOtpRegisteredNumbersOnly()).isFalse();
    }

    @Test
    void valeurIllisibleEnBase_resteReserveAuxInscrits() {
        when(cache.all()).thenReturn(Map.of(KEY, "oui"));
        assertThat(service.smsOtpRegisteredNumbersOnly()).isTrue();
    }

    @Test
    void desactivation_normaliseAuditeEtVideLeCache() {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, "true", PlatformSettingType.BOOLEAN);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.SMS_OTP_REGISTERED_NUMBERS_ONLY, "FALSE"), admin);

        assertThat(row.getSettingValue()).isEqualTo("false");
        assertThat(row.getUpdatedBy()).isEqualTo(admin);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin), any());
        verify(cache).evict();
    }

    @Test
    void valeurNonBooleenne_refusee422() {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SMS_OTP_REGISTERED_NUMBERS_ONLY, "oui"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("attend true ou false");
        verifyNoInteractions(auditService);
    }

    @Test
    void ligneManquante_vueAdminServieAvecLaValeurEffective() {
        when(cache.all()).thenReturn(Map.of());
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.listByKey())
                .filteredOn(v -> v.key() == PlatformSettingKey.SMS_OTP_REGISTERED_NUMBERS_ONLY)
                .singleElement()
                .satisfies(v -> assertThat(v.value()).isEqualTo("true"));
    }

    @Test
    void cleConnueDuContratAdmin() {
        assertThat(PlatformSettingKey.fromKey(KEY)).isEqualTo(PlatformSettingKey.SMS_OTP_REGISTERED_NUMBERS_ONLY);
        assertThat(PlatformSettingKey.SMS_OTP_REGISTERED_NUMBERS_ONLY.type()).isEqualTo(PlatformSettingType.BOOLEAN);
    }
}
