package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Un drapeau booleen lu depuis une variable d'environnement <em>declaree mais vide</em> ne
 * doit jamais faire echouer le demarrage.
 *
 * <p>Le 2026-09-05, le staging est tombe au demarrage avec « Invalid boolean value [] » :
 * le workflow de deploiement ecrivait {@code KYC_DIDIT_ENABLED=} (variable GitHub absente),
 * ce qui DEFINIT la propriete a vide — le defaut {@code :false} ne s'appliquait donc pas, et
 * la conversion vers {@code boolean} faisait tomber tout le contexte Spring.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformSettingsBooleanPropertyTest {

    @Mock PlatformSettingRepository repository;
    @Mock PlatformSettingsCache cache;
    @Mock AuditService auditService;
    @Mock YadonyConfigProperties config;

    private PlatformSettingsService service(String valeurBrute) {
        when(cache.all()).thenReturn(Map.of());
        return new PlatformSettingsService(repository, cache, auditService, config,
                false, false, valeurBrute);
    }

    @Test
    void chaineVide_vautFaux_etNeLevePas() {
        assertThat(service("").kycDiditEnabled()).isFalse();
    }

    @Test
    void chaineDEspaces_vautFaux() {
        assertThat(service("   ").kycDiditEnabled()).isFalse();
    }

    @Test
    void valeurNulle_vautFaux() {
        assertThat(service(null).kycDiditEnabled()).isFalse();
    }

    @Test
    void vrai_estLu() {
        assertThat(service("true").kycDiditEnabled()).isTrue();
        assertThat(service(" TRUE ").kycDiditEnabled()).isTrue();
    }

    @Test
    void faux_estLu() {
        assertThat(service("false").kycDiditEnabled()).isFalse();
    }

    /** Une valeur incomprehensible eteint le drapeau plutot que de tuer le demarrage. */
    @Test
    void valeurIncomprehensible_vautFaux() {
        assertThat(service("peut-etre").kycDiditEnabled()).isFalse();
    }

    /** La ligne en base reste prioritaire sur la property, vide ou non. */
    @Test
    void laLigneEnBaseFaitAutorite() {
        when(cache.all()).thenReturn(Map.of(PlatformSettingKey.KYC_DIDIT_ENABLED.key(), "true"));
        PlatformSettingsService avecLigne = new PlatformSettingsService(
                repository, cache, auditService, config, false, false, "");

        assertThat(avecLigne.kycDiditEnabled()).isTrue();
    }
}
