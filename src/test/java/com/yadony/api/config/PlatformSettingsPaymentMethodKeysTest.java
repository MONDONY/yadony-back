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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Drapeaux des moyens de paiement de la feuille (YADONY-FLUTTER-PROD-11), pilotés depuis le back-office. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsPaymentMethodKeysTest {

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
    void lignesAbsentes_toutEstEteint() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.paypalEnabled()).isFalse();
        assertThat(service.applePayEnabled()).isFalse();
        assertThat(service.googlePayEnabled()).isFalse();
    }

    @Test
    void chaqueDrapeauEstIndependant() {
        when(cache.all()).thenReturn(Map.of(
                "payments_paypal_enabled", "false",
                "payments_apple_pay_enabled", "true",
                "payments_google_pay_enabled", "false"));
        assertThat(service.paypalEnabled()).isFalse();
        assertThat(service.applePayEnabled()).isTrue();
        assertThat(service.googlePayEnabled()).isFalse();
    }

    @Test
    void valeurIllisibleEnBase_vautFaux() {
        when(cache.all()).thenReturn(Map.of("payments_google_pay_enabled", "oui"));
        assertThat(service.googlePayEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"payments_paypal_enabled", "payments_apple_pay_enabled", "payments_google_pay_enabled"})
    void activation_normaliseAuditeEtVideLeCache(String key) {
        PlatformSettingEntity row = new PlatformSettingEntity(key, "false", PlatformSettingType.BOOLEAN);
        when(repository.findBySettingKey(key)).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.fromKey(key), "TRUE"), admin);

        assertThat(row.getSettingValue()).isEqualTo("true");
        assertThat(row.getUpdatedBy()).isEqualTo(admin);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin), any());
        verify(cache).evict();
    }

    @ParameterizedTest
    @ValueSource(strings = {"payments_paypal_enabled", "payments_apple_pay_enabled", "payments_google_pay_enabled"})
    void valeurNonBooleenne_refusee422(String key) {
        assertThatThrownBy(() -> service.update(Map.of(PlatformSettingKey.fromKey(key), "oui"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("attend true ou false");
        verifyNoInteractions(auditService);
    }

    @Test
    void ligneManquante_vueAdminServieAvecLaValeurEffective() {
        when(cache.all()).thenReturn(Map.of());
        when(repository.findAll()).thenReturn(java.util.List.of());
        assertThat(service.listByKey())
                .filteredOn(v -> v.key() == PlatformSettingKey.PAYMENTS_APPLE_PAY_ENABLED)
                .singleElement()
                .satisfies(v -> assertThat(v.value()).isEqualTo("false"));
    }
}
