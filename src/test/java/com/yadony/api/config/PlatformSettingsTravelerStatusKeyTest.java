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

/** Interrupteur « statut du voyageur obligatoire pour publier un trajet », réglable depuis l'admin. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsTravelerStatusKeyTest {

    private static final String KEY = "traveler_status_required";

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
    void inactifParDefaut_quandLaLigneManque() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.travelerStatusRequired()).isFalse();
    }

    @Test
    void litLaValeurEnregistree() {
        when(cache.all()).thenReturn(Map.of(KEY, "true"));
        assertThat(service.travelerStatusRequired()).isTrue();
        when(cache.all()).thenReturn(Map.of(KEY, "false"));
        assertThat(service.travelerStatusRequired()).isFalse();
    }

    @Test
    void valeurIllisibleEnBase_resteInactive() {
        when(cache.all()).thenReturn(Map.of(KEY, "oui"));
        assertThat(service.travelerStatusRequired()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"oui", "1", "yes", "vrai", "truee"})
    void refuseUneValeurNonBooleenne_422_etNEcritRien(String value) {
        assertThatThrownBy(() -> service.updateOne(PlatformSettingKey.TRAVELER_STATUS_REQUIRED, value,
                UUID.randomUUID()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("platform-setting-invalid");
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                });
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @CsvSource({"true", "false", "TRUE"})
    void accepteTrueEtFalse_normalise_etAudite(String value) {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, value.equalsIgnoreCase("true") ? "false" : "true",
                PlatformSettingType.BOOLEAN);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));
        when(repository.findAll()).thenReturn(List.of(row));
        when(cache.all()).thenReturn(Map.of());

        service.updateOne(PlatformSettingKey.TRAVELER_STATUS_REQUIRED, " " + value + " ", UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo(value.toLowerCase());
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), any(), any());
    }

    @Test
    void cleDeTypeBooleen() {
        assertThat(PlatformSettingKey.fromKey(KEY)).isEqualTo(PlatformSettingKey.TRAVELER_STATUS_REQUIRED);
        assertThat(PlatformSettingKey.TRAVELER_STATUS_REQUIRED.type()).isEqualTo(PlatformSettingType.BOOLEAN);
    }

    @Test
    void vueAdmin_servieSansLigne_valeurFalse() {
        when(repository.findAll()).thenReturn(List.of());
        when(cache.all()).thenReturn(Map.of());

        var view = service.listByKey().stream()
                .filter(v -> v.key() == PlatformSettingKey.TRAVELER_STATUS_REQUIRED).findFirst().orElseThrow();

        assertThat(view.value()).isEqualTo("false");
    }
}
