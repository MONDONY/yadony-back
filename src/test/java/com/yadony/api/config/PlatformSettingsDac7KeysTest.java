package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Interrupteurs DAC7 (collecte et messages), pilotés depuis le back-office : éteints par défaut, audités. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Réglages DAC7 : collecte et messages")
class PlatformSettingsDac7KeysTest {

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
    @DisplayName("lignes absentes : tout est éteint (la position sûre)")
    void lignesAbsentes_toutEstEteint() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.dac7Enabled()).isFalse();
        assertThat(service.dac7RemindersEnabled()).isFalse();
    }

    @Test
    @DisplayName("chaque interrupteur est indépendant ; une valeur illisible en base vaut faux")
    void interrupteursIndependants() {
        when(cache.all()).thenReturn(Map.of("dac7_enabled", "true", "dac7_reminders_enabled", "oui"));
        assertThat(service.dac7Enabled()).isTrue();
        assertThat(service.dac7RemindersEnabled()).isFalse();
    }

    @Test
    @DisplayName("allumer la collecte : normalisé, audité (auteur, ancienne et nouvelle valeur), cache vidé")
    void activationCollecte_auditeeEtCacheVide() {
        PlatformSettingEntity row = new PlatformSettingEntity("dac7_enabled", "false", PlatformSettingType.BOOLEAN);
        when(repository.findBySettingKey("dac7_enabled")).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.DAC7_ENABLED, "TRUE"), admin);

        assertThat(row.getSettingValue()).isEqualTo("true");
        assertThat(row.getUpdatedBy()).isEqualTo(admin);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin), any());
        verify(cache).evict();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dac7_enabled", "dac7_reminders_enabled"})
    @DisplayName("valeur non booléenne : refusée en 422, rien d'audité")
    void valeurNonBooleenne_refusee(String key) {
        assertThatThrownBy(() -> service.update(Map.of(PlatformSettingKey.fromKey(key), "oui"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("attend true ou false");
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("messages allumés alors que la collecte est éteinte : refusé en 422, rien n'est écrit")
    void messagesSansCollecte_refuses() {
        when(cache.all()).thenReturn(Map.of("dac7_enabled", "false"));

        assertThatThrownBy(() -> service.update(Map.of(PlatformSettingKey.DAC7_REMINDERS_ENABLED, "true"),
                UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("exigent la collecte");
        verifyNoInteractions(auditService);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("messages et collecte allumés dans la même demande : accepté")
    void messagesEtCollecteEnsemble_acceptes() {
        PlatformSettingEntity collection = new PlatformSettingEntity("dac7_enabled", "false",
                PlatformSettingType.BOOLEAN);
        PlatformSettingEntity reminders = new PlatformSettingEntity("dac7_reminders_enabled", "false",
                PlatformSettingType.BOOLEAN);
        when(repository.findBySettingKey("dac7_enabled")).thenReturn(Optional.of(collection));
        when(repository.findBySettingKey("dac7_reminders_enabled")).thenReturn(Optional.of(reminders));

        service.update(Map.of(PlatformSettingKey.DAC7_ENABLED, "true", PlatformSettingKey.DAC7_REMINDERS_ENABLED,
                "true"), UUID.randomUUID());

        assertThat(collection.getSettingValue()).isEqualTo("true");
        assertThat(reminders.getSettingValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("messages allumés quand la collecte l'est déjà : accepté ; les éteindre est toujours permis")
    void messagesAvecCollecteDejaAllumee() {
        when(cache.all()).thenReturn(Map.of("dac7_enabled", "true"));
        PlatformSettingEntity reminders = new PlatformSettingEntity("dac7_reminders_enabled", "false",
                PlatformSettingType.BOOLEAN);
        when(repository.findBySettingKey("dac7_reminders_enabled")).thenReturn(Optional.of(reminders));

        service.update(Map.of(PlatformSettingKey.DAC7_REMINDERS_ENABLED, "true"), UUID.randomUUID());
        assertThat(reminders.getSettingValue()).isEqualTo("true");

        service.update(Map.of(PlatformSettingKey.DAC7_REMINDERS_ENABLED, "false"), UUID.randomUUID());
        assertThat(reminders.getSettingValue()).isEqualTo("false");
    }

    @Test
    @DisplayName("ligne manquante : la vue admin montre éteint")
    void ligneManquante_vueAdminEteinte() {
        when(cache.all()).thenReturn(Map.of());
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.listByKey()).filteredOn(v -> v.key() == PlatformSettingKey.DAC7_ENABLED
                        || v.key() == PlatformSettingKey.DAC7_REMINDERS_ENABLED)
                .hasSize(2).allSatisfy(v -> assertThat(v.value()).isEqualTo("false"));
    }

    @Test
    @DisplayName("amorçage depuis les variables : une fois, jamais d'écrasement ; messages allumés seulement avec la collecte")
    void amorcage() {
        when(repository.findBySettingKey(any())).thenReturn(Optional.empty());

        seed("true", "true").seedMissingKeys();
        verify(repository).save(argThat(e -> "dac7_enabled".equals(e.getSettingKey()) && "true".equals(e.getSettingValue())));
        verify(repository).save(argThat(e -> "dac7_reminders_enabled".equals(e.getSettingKey())
                && "true".equals(e.getSettingValue())));

        reset(repository);
        when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
        seed("false", "true").seedMissingKeys();
        verify(repository).save(argThat(e -> "dac7_reminders_enabled".equals(e.getSettingKey())
                && "false".equals(e.getSettingValue())));

        reset(repository);
        when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
        seed("", "").seedMissingKeys();
        verify(repository).save(argThat(e -> "dac7_enabled".equals(e.getSettingKey())
                && "false".equals(e.getSettingValue())));

        reset(repository);
        when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
        when(repository.findBySettingKey("dac7_enabled")).thenReturn(
                Optional.of(new PlatformSettingEntity("dac7_enabled", "false", PlatformSettingType.BOOLEAN)));
        seed("true", "true").seedMissingKeys();
        verify(repository, never()).save(argThat(e -> "dac7_enabled".equals(e.getSettingKey())));
    }

    private PlatformSettingsInitializer seed(String enabled, String reminders) {
        return new PlatformSettingsInitializer(repository, config, false, false, false, false, "", false, "20", "30",
                "", "", "", "true", "3", "30", "150", "", enabled, reminders);
    }
}
