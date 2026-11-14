package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Numéro WhatsApp de l'équipe Yadony, réglé depuis le back-office et affiché en tête du Support de l'app. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsSupportWhatsappKeyTest {

    private static final String KEY = "support_whatsapp_number";

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
    void ligneAbsente_aucunNumero() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.supportWhatsappNumber()).isEmpty();
    }

    @Test
    void ligneVide_aucunNumero() {
        when(cache.all()).thenReturn(Map.of(KEY, "  "));
        assertThat(service.supportWhatsappNumber()).isEmpty();
    }

    @Test
    void numeroRegle_servi() {
        when(cache.all()).thenReturn(Map.of(KEY, "+33612345678"));
        assertThat(service.supportWhatsappNumber()).contains("+33612345678");
    }

    @Test
    void cleDeclareeCommeNumeroInternational_videAccepte() {
        assertThat(PlatformSettingKey.fromKey(KEY)).isEqualTo(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER);
        assertThat(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER.type()).isEqualTo(PlatformSettingType.PHONE_E164);
        assertThat(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER.acceptsEmptyValue()).isTrue();
    }

    // ── Écriture ─────────────────────────────────────────────────────────────

    @Test
    void saisieAvecEspacesEtPoints_normaliseeAuditeeEtCacheVide() {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, "", PlatformSettingType.PHONE_E164);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER, " +33 6 12.34-56 78 "), admin);

        assertThat(row.getSettingValue()).isEqualTo("+33612345678");
        assertThat(row.getUpdatedBy()).isEqualTo(admin);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin),
                argThat(payload -> "+33612345678".equals(payload.get("newValue"))
                        && "".equals(payload.get("oldValue"))));
        verify(cache).evict();
    }

    @Test
    void prefixeDoubleZero_converti() {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, "", PlatformSettingType.PHONE_E164);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));

        service.update(Map.of(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER, "0033612345678"), UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo("+33612345678");
    }

    @Test
    void valeurVide_accepteeLaCarteDisparaitDeLApp() {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, "+33612345678", PlatformSettingType.PHONE_E164);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));

        service.update(Map.of(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER, "  "), UUID.randomUUID());

        assertThat(row.getSettingValue()).isEmpty();
        verify(cache).evict();
    }

    @Test
    void numeroSansIndicatif_refuse422SansAudit() {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER, "0612345678"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getErrorCode()).isEqualTo("platform-setting-invalid");
                    assertThat(ex.getMessage()).contains("international");
                });
        verifyNoInteractions(auditService);
    }

    @Test
    void numeroAvecLettresOuTropCourt_refuse() {
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER, "+33abc12345"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class);
        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.SUPPORT_WHATSAPP_NUMBER, "+3361"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class);
    }
}
