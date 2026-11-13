package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/** Lot 4 : seuil d'alerte « particulier qui voyage comme un professionnel », réglable depuis le panel admin. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsIndividualTripsAlertKeyTest {

    private static final String KEY = "individual_trips_alert_threshold";

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
    void cleDeTypeEntier() {
        assertThat(PlatformSettingKey.fromKey(KEY)).isEqualTo(PlatformSettingKey.INDIVIDUAL_TRIPS_ALERT_THRESHOLD);
        assertThat(PlatformSettingKey.INDIVIDUAL_TRIPS_ALERT_THRESHOLD.type()).isEqualTo(PlatformSettingType.INTEGER);
        assertThat(PlatformSettingKey.DEFAULT_INDIVIDUAL_TRIPS_ALERT_THRESHOLD).isEqualTo(4);
    }

    @Test
    void defaut4_quandLaLigneManque() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.individualTripsAlertThreshold()).isEqualTo(4);
    }

    @Test
    void litLaValeurEnregistree() {
        when(cache.all()).thenReturn(Map.of(KEY, "7"));
        assertThat(service.individualTripsAlertThreshold()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"beaucoup", "0", "-1", "101", "", " "})
    void valeurIllisibleVideOuHorsBornesEnBase_retombeSurLeDefaut_jamaisZero(String raw) {
        when(cache.all()).thenReturn(Map.of(KEY, raw));
        assertThat(service.individualTripsAlertThreshold()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-3", "abc", "2.5", "", " "})
    void refuseHorsBornesOuVide_etNEcritRien(String value) {
        assertThatThrownBy(() -> service.updateOne(PlatformSettingKey.INDIVIDUAL_TRIPS_ALERT_THRESHOLD, value,
                UUID.randomUUID())).isInstanceOf(YadonyBusinessException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @CsvSource({"1", "100"})
    void accepteLesBornes_etAudite(String value) {
        PlatformSettingEntity row = new PlatformSettingEntity(KEY, "4", PlatformSettingType.INTEGER);
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(row));
        when(repository.findAll()).thenReturn(List.of(row));
        when(cache.all()).thenReturn(Map.of());

        service.updateOne(PlatformSettingKey.INDIVIDUAL_TRIPS_ALERT_THRESHOLD, " " + value + " ", UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo(value);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), any(), any());
    }

    @Test
    void ligneManquante_vueAdmin_montreLeDefaut() {
        when(repository.findAll()).thenReturn(List.of());
        when(cache.all()).thenReturn(Map.of());

        assertThat(service.listByKey()).anySatisfy(v -> {
            assertThat(v.key()).isEqualTo(PlatformSettingKey.INDIVIDUAL_TRIPS_ALERT_THRESHOLD);
            assertThat(v.value()).isEqualTo("4");
        });
    }

    @Test
    void amorcage_seme4_sansEcraserUneValeurExistante() {
        PlatformSettingsInitializer initializer = new PlatformSettingsInitializer(repository, config,
                false, false, false, false, "", false, "20", "30", "", "", "", "true", "3", "30", "150", "", "", "");
        when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.empty());

        initializer.seedMissingKeys();

        verify(repository).save(argThat(e -> KEY.equals(e.getSettingKey()) && "4".equals(e.getSettingValue())
                && e.getValueType() == PlatformSettingType.INTEGER));

        reset(repository);
        when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
        when(repository.findBySettingKey(KEY)).thenReturn(
                Optional.of(new PlatformSettingEntity(KEY, "9", PlatformSettingType.INTEGER)));

        initializer.seedMissingKeys();

        verify(repository, never()).save(argThat(e -> KEY.equals(e.getSettingKey())));
    }
}
