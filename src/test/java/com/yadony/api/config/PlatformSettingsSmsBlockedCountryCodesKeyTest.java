package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
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
 * Indicatifs vers lesquels aucun SMS ne part, pilotés depuis le back-office : les SMS Twilio vers
 * l'Afrique étaient facturés et marqués « Delivered » sans jamais arriver (08/11/2026).
 */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsSmsBlockedCountryCodesKeyTest {

    private static final String KEY = "sms_blocked_country_codes";
    private static final String DEFAULT =
            "212,213,216,221,222,223,224,225,226,227,228,229,235,236,237,241,242,243";

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

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Test
    void ligneAbsente_listeParDefautDesIndicatifsAfricains() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(String.join(",", service.smsBlockedCountryCodes())).isEqualTo(DEFAULT);
        assertThat(PlatformSettingKey.DEFAULT_SMS_BLOCKED_COUNTRY_CODES).isEqualTo(DEFAULT);
    }

    @Test
    void ligneVide_aucunPaysBloque() {
        when(cache.all()).thenReturn(Map.of(KEY, ""));
        assertThat(service.smsBlockedCountryCodes()).isEmpty();
        assertThat(service.smsBlockedCallingCode("+2250701020304")).isEmpty();
    }

    @Test
    void indicatifBloque_reconnuAuDebutDuNumero() {
        when(cache.all()).thenReturn(Map.of(KEY, "221,225"));
        assertThat(service.smsBlockedCallingCode("+2250701020304")).contains("225");
        assertThat(service.smsBlockedCallingCode("+221701234567")).contains("221");
        assertThat(service.smsBlockedCallingCode("+33612345678")).isEmpty();
        assertThat(service.smsBlockedCallingCode("+237612345678")).isEmpty();
    }

    @Test
    void lectureAChaqueAppel_modificationAdminPriseEnCompteSansRedemarrage() {
        when(cache.all()).thenReturn(Map.of(KEY, "225"), Map.of(KEY, ""));
        assertThat(service.smsBlockedCallingCode("+2250701020304")).contains("225");
        assertThat(service.smsBlockedCallingCode("+2250701020304")).isEmpty();
    }

    @Test
    void valeurEnBasePartiellementIllisible_entreesValidesGardees() {
        when(cache.all()).thenReturn(Map.of(KEY, "225,abc"));
        assertThat(service.smsBlockedCountryCodes()).containsExactly("225");
    }

    // ── Écriture (PUT /admin/settings/sms_blocked_country_codes) ─────────────

    @Test
    void saisieAvecPlusEspacesEtDoublons_normaliseeAuditeeEtCacheVide() {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, DEFAULT, PlatformSettingType.CALLING_CODES);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES, " +225, 221 ,225 "), admin);

        assertThat(row.getSettingValue()).isEqualTo("225,221");
        assertThat(row.getUpdatedBy()).isEqualTo(admin);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin),
                argThat(payload -> "225,221".equals(payload.get("newValue"))
                        && DEFAULT.equals(payload.get("oldValue"))));
        verify(cache).evict();
    }

    @Test
    void valeurVide_accepteeAucunPaysBloque() {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, DEFAULT, PlatformSettingType.CALLING_CODES);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));

        service.update(Map.of(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES, "  "), UUID.randomUUID());

        assertThat(row.getSettingValue()).isEmpty();
        verify(cache).evict();
    }

    @Test
    void indicatifInvalide_refuse422SansAudit() {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES, "225,22a"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getErrorCode()).isEqualTo("platform-setting-invalid");
                    assertThat(ex.getMessage()).contains("22a");
                });
        verifyNoInteractions(auditService);
    }

    @Test
    void plusDe60Indicatifs_refuse422() {
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 61; i++) {
            many.append(i).append(',');
        }
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES, many.toString()), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("60");
    }

    @Test
    void valeurNulle_refusee422() {
        Map<PlatformSettingKey, String> changes = new HashMap<>();
        changes.put(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES, null);
        assertThatThrownBy(() -> service.update(changes, UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("obligatoire");
    }

    @Test
    void valeurVide_toujoursRefuseePourLesAutresReglages() {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SMS_OTP_MAX_SENDS_PER_HOUR_GLOBAL, " "), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("obligatoire");
    }

    // ── Vue admin et contrat ─────────────────────────────────────────────────

    @Test
    void ligneManquante_vueAdminServieAvecLaListeParDefaut() {
        when(cache.all()).thenReturn(Map.of());
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.listByKey())
                .filteredOn(v -> v.key() == PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES)
                .singleElement()
                .satisfies(v -> assertThat(v.value()).isEqualTo(DEFAULT));
    }

    @Test
    void cleConnueDuContratAdmin_typeCallingCodes() {
        assertThat(PlatformSettingKey.fromKey(KEY)).isEqualTo(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES);
        assertThat(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES.type()).isEqualTo(PlatformSettingType.CALLING_CODES);
        assertThat(PlatformSettingKey.SMS_BLOCKED_COUNTRY_CODES.acceptsEmptyValue()).isTrue();
        assertThat(PlatformSettingKey.SMS_ENABLED.acceptsEmptyValue()).isFalse();
    }

    // ── Amorçage depuis YADONY_SMS_BLOCKED_COUNTRY_CODES ─────────────────────

    @Test
    void amorcage_videOuIllisible_listeParDefaut() {
        assertThat(PlatformSettingsInitializer.blockedCountryCodesOr(null)).isEqualTo(DEFAULT);
        assertThat(PlatformSettingsInitializer.blockedCountryCodesOr("  ")).isEqualTo(DEFAULT);
        assertThat(PlatformSettingsInitializer.blockedCountryCodesOr("22a")).isEqualTo(DEFAULT);
        assertThat(PlatformSettingsInitializer.blockedCountryCodesOr(" , ")).isEqualTo(DEFAULT);
    }

    @Test
    void amorcage_noneExplicite_aucunPaysBloque() {
        assertThat(PlatformSettingsInitializer.blockedCountryCodesOr(" NONE ")).isEmpty();
    }

    @Test
    void amorcage_listeValide_normalisee() {
        assertThat(PlatformSettingsInitializer.blockedCountryCodesOr("+225, 221")).isEqualTo("225,221");
    }
}
