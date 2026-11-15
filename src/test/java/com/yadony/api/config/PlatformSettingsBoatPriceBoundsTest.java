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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Bornes du prix au m³ d'un trajet bateau (lot 4), réglées depuis le back-office. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsBoatPriceBoundsTest {

    private static final String HARD_MIN = "boat_price_per_m3_hard_min_eur";
    private static final String HARD_MAX = "boat_price_per_m3_hard_max_eur";
    private static final String ADVISED_MIN = "boat_price_per_m3_advised_min_eur";
    private static final String ADVISED_MAX = "boat_price_per_m3_advised_max_eur";
    private static final String REFERENCE = "boat_price_per_m3_reference_eur";

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

    private static Map<String, String> rows(String hardMin, String hardMax, String advisedMin, String advisedMax,
                                            String reference) {
        Map<String, String> rows = new HashMap<>();
        rows.put(HARD_MIN, hardMin);
        rows.put(HARD_MAX, hardMax);
        rows.put(ADVISED_MIN, advisedMin);
        rows.put(ADVISED_MAX, advisedMax);
        rows.put(REFERENCE, reference);
        return rows;
    }

    // ── Clés ─────────────────────────────────────────────────────────────────

    @Test
    void cinqClesDecimales() {
        assertThat(List.of(HARD_MIN, HARD_MAX, ADVISED_MIN, ADVISED_MAX, REFERENCE))
                .allSatisfy(key -> {
                    PlatformSettingKey k = PlatformSettingKey.fromKey(key);
                    assertThat(k.type()).isEqualTo(PlatformSettingType.DECIMAL);
                    assertThat(k.acceptsEmptyValue()).isFalse();
                });
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Test
    void lignesAbsentes_defauts() {
        when(cache.all()).thenReturn(Map.of());

        BoatPricePerM3Bounds bounds = service.boatPricePerM3Bounds();

        assertThat(bounds).isEqualTo(BoatPricePerM3Bounds.DEFAULTS);
        assertThat(bounds.hardMinEur()).isEqualByComparingTo("100");
        assertThat(bounds.hardMaxEur()).isEqualByComparingTo("600");
        assertThat(bounds.advisedMinEur()).isEqualByComparingTo("150");
        assertThat(bounds.advisedMaxEur()).isEqualByComparingTo("400");
        assertThat(bounds.referenceEur()).isEqualByComparingTo("240");
    }

    @Test
    void valeursReglees_servies() {
        when(cache.all()).thenReturn(rows("80", "900", "120", "500", "260.50"));

        BoatPricePerM3Bounds bounds = service.boatPricePerM3Bounds();

        assertThat(bounds.hardMinEur()).isEqualByComparingTo("80");
        assertThat(bounds.hardMaxEur()).isEqualByComparingTo("900");
        assertThat(bounds.advisedMinEur()).isEqualByComparingTo("120");
        assertThat(bounds.advisedMaxEur()).isEqualByComparingTo("500");
        assertThat(bounds.referenceEur()).isEqualByComparingTo("260.50");
    }

    @Test
    void valeurIllisibleVideOuHorsBornes_defautDeLaSeuleCle() {
        when(cache.all()).thenReturn(rows("abc", " ", "0", "100001", "240"));

        BoatPricePerM3Bounds bounds = service.boatPricePerM3Bounds();

        assertThat(bounds).isEqualTo(BoatPricePerM3Bounds.DEFAULTS);

        when(cache.all()).thenReturn(rows("abc", "700", "150", "400", "240"));
        assertThat(service.boatPricePerM3Bounds().hardMinEur()).isEqualByComparingTo("100");
        assertThat(service.boatPricePerM3Bounds().hardMaxEur()).isEqualByComparingTo("700");
    }

    @Test
    void ensembleIncoherent_tousLesDefauts() {
        // Prix indicatif au-dessus de la zone conseillée : jamais servi tel quel.
        when(cache.all()).thenReturn(rows("100", "600", "150", "400", "450"));

        assertThat(service.boatPricePerM3Bounds()).isEqualTo(BoatPricePerM3Bounds.DEFAULTS);
    }

    @Test
    void ligneManquante_vueBackOfficeAvecValeurEffective() {
        when(cache.all()).thenReturn(Map.of(HARD_MAX, "750"));
        when(repository.findAll()).thenReturn(List.of());

        Map<PlatformSettingKey, String> values = new HashMap<>();
        service.listByKey().forEach(view -> values.put(view.key(), view.value()));

        assertThat(values.get(PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MIN_EUR)).isEqualTo("100");
        assertThat(values.get(PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MAX_EUR)).isEqualTo("750");
        assertThat(values.get(PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MIN_EUR)).isEqualTo("150");
        assertThat(values.get(PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MAX_EUR)).isEqualTo("400");
        assertThat(values.get(PlatformSettingKey.BOAT_PRICE_PER_M3_REFERENCE_EUR)).isEqualTo("240");
    }

    // ── Écriture ─────────────────────────────────────────────────────────────

    @Test
    void saisieValide_normaliseeAuditeeEtCacheVide() {
        when(cache.all()).thenReturn(Map.of());
        PlatformSettingEntity row = new PlatformSettingEntity(HARD_MAX, "600", PlatformSettingType.DECIMAL);
        when(repository.findBySettingKey(HARD_MAX)).thenReturn(Optional.of(row));
        UUID admin = UUID.randomUUID();

        service.update(Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MAX_EUR, " 800.00 "), admin);

        assertThat(row.getSettingValue()).isEqualTo("800");
        assertThat(row.getUpdatedBy()).isEqualTo(admin);
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), eq(admin), any());
        verify(cache).evict();
    }

    @Test
    void centimesConserves() {
        when(cache.all()).thenReturn(Map.of());
        PlatformSettingEntity row = new PlatformSettingEntity(REFERENCE, "240", PlatformSettingType.DECIMAL);
        when(repository.findBySettingKey(REFERENCE)).thenReturn(Optional.of(row));

        service.update(Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_REFERENCE_EUR, "245.50"), UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo("245.5");
    }

    @Test
    void horsBornesDeSaisieIllisibleOuMillimes_refuse422SansAudit() {
        for (String value : List.of("0", "0.99", "100000.01", "-5", "abc", "240.555")) {
            assertThatThrownBy(() -> service.update(
                    Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_REFERENCE_EUR, value), UUID.randomUUID()))
                    .as(value)
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus().value()).isEqualTo(422);
                        assertThat(ex.getErrorCode()).isEqualTo("platform-setting-invalid");
                    });
        }
        verifyNoInteractions(auditService);
        verify(repository, never()).save(any());
    }

    @Test
    void bornesExtremesDeSaisie_acceptees() {
        when(cache.all()).thenReturn(Map.of());
        PlatformSettingEntity min = new PlatformSettingEntity(HARD_MIN, "100", PlatformSettingType.DECIMAL);
        PlatformSettingEntity max = new PlatformSettingEntity(HARD_MAX, "600", PlatformSettingType.DECIMAL);
        when(repository.findBySettingKey(HARD_MIN)).thenReturn(Optional.of(min));
        when(repository.findBySettingKey(HARD_MAX)).thenReturn(Optional.of(max));

        service.update(Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MIN_EUR, "1",
                PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MAX_EUR, "100000"), UUID.randomUUID());

        assertThat(min.getSettingValue()).isEqualTo("1");
        assertThat(max.getSettingValue()).isEqualTo("100000");
    }

    @Test
    void contrainteCroisee_borneMinAuDessusDeLaZoneConseillee_refusee() {
        when(cache.all()).thenReturn(Map.of());

        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MIN_EUR, "200"), UUID.randomUUID()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getErrorCode()).isEqualTo("platform-setting-invalid");
                    assertThat(ex.getMessage()).contains("ordre");
                });
        verifyNoInteractions(auditService);
        verify(repository, never()).save(any());
    }

    @Test
    void contrainteCroisee_chaqueInversionRefusee() {
        when(cache.all()).thenReturn(Map.of());
        List<Map<PlatformSettingKey, String>> inversions = List.of(
                Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MIN_EUR, "250"),
                Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_REFERENCE_EUR, "401"),
                Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MAX_EUR, "601"),
                Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MAX_EUR, "399"));
        for (Map<PlatformSettingKey, String> change : inversions) {
            assertThatThrownBy(() -> service.update(change, UUID.randomUUID()))
                    .as(change.toString())
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo("platform-setting-invalid"));
        }
        verifyNoInteractions(auditService);
    }

    @Test
    void contrainteCroisee_plusieursClesDansLaMemeDemande_jugeesEnsemble() {
        when(cache.all()).thenReturn(Map.of());
        Map<String, PlatformSettingEntity> stored = new HashMap<>();
        for (String key : List.of(HARD_MIN, ADVISED_MIN)) {
            PlatformSettingEntity row = new PlatformSettingEntity(key, "x", PlatformSettingType.DECIMAL);
            stored.put(key, row);
            when(repository.findBySettingKey(key)).thenReturn(Optional.of(row));
        }

        // Seule, hard_min = 200 serait refusée (advised_min = 150) ; avec advised_min = 200, l'ordre tient.
        service.update(Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MIN_EUR, "200",
                PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MIN_EUR, "200"), UUID.randomUUID());

        assertThat(stored.get(HARD_MIN).getSettingValue()).isEqualTo("200");
        assertThat(stored.get(ADVISED_MIN).getSettingValue()).isEqualTo("200");
    }

    @Test
    void contrainteCroisee_jugeeSurLesValeursEnTable() {
        // hard_max déjà à 300 en table : advised_max = 350 est refusé même si le défaut (600) l'aurait permis.
        when(cache.all()).thenReturn(Map.of(HARD_MAX, "300", ADVISED_MAX, "300", REFERENCE, "240"));

        assertThatThrownBy(() -> service.update(
                Map.of(PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MAX_EUR, "350"), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void autresCles_nonConcerneesParLaContrainte() {
        PlatformSettingEntity row = new PlatformSettingEntity("sms_enabled", "false", PlatformSettingType.BOOLEAN);
        when(repository.findBySettingKey("sms_enabled")).thenReturn(Optional.of(row));

        service.update(Map.of(PlatformSettingKey.SMS_ENABLED, "true"), UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo("true");
    }
}
