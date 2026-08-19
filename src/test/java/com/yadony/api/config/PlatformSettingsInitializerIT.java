package com.yadony.api.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D — l'amorcage se fait a l'EXECUTION, depuis les properties resolues, jamais dans
 * la migration SQL.
 *
 * <p>Raison : une migration ne voit pas {@code SMS_ENABLED} ni {@code YADONY_COMMISSION_RATE},
 * qui sont des variables d'environnement. Un {@code INSERT ... 'false'} en dur aurait coupe
 * l'OTP SMS en production des le deploiement. Accessoirement, Flyway est desactive dans le
 * profil de test : ce mecanisme est aussi le seul qui garnit la table ici.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PlatformSettingsInitializerIT — amorcage des parametres plateforme")
class PlatformSettingsInitializerIT {

    @Autowired PlatformSettingsInitializer initializer;
    @Autowired PlatformSettingRepository repository;
    @Autowired YadonyConfigProperties config;

    /**
     * ⚠️ @AfterEach autant que @BeforeEach : la H2 de test est PARTAGEE entre contextes
     * ({@code DB_CLOSE_DELAY=-1}). On rend la table a son etat amorce pour ne pas laisser
     * une valeur bricolee derriere soi.
     */
    @BeforeEach
    void clearTable() {
        repository.deleteAll();
    }

    @AfterEach
    void restoreSeededState() {
        repository.deleteAll();
        initializer.seedMissingKeys();
    }

    @Test
    @DisplayName("insere les quatre cles depuis les properties resolues")
    void seedsAllFourKeysFromProperties() {
        int inserted = initializer.seedMissingKeys();

        assertThat(inserted).isEqualTo(4);
        assertThat(repository.findBySettingKey("commission_rate")).isPresent()
                .get().extracting(PlatformSettingEntity::getSettingValue)
                .isEqualTo(config.commission().rate().toPlainString());
        assertThat(repository.findBySettingKey("urgency_threshold_days")).isPresent()
                .get().extracting(PlatformSettingEntity::getSettingValue)
                .isEqualTo(String.valueOf(config.urgency().thresholdDays()));
        assertThat(repository.findBySettingKey("reimbursement_cap_eur")).isPresent()
                .get().extracting(PlatformSettingEntity::getSettingValue)
                .isEqualTo(config.reimbursement().maxAmountEur().toPlainString());
        assertThat(repository.findBySettingKey("sms_enabled")).isPresent()
                .get().extracting(PlatformSettingEntity::getSettingValue)
                .isEqualTo("false");
    }

    @Test
    @DisplayName("idempotent : un second passage n'insere rien")
    void secondRunInsertsNothing() {
        initializer.seedMissingKeys();

        assertThat(initializer.seedMissingKeys()).isZero();
        assertThat(repository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("n'ecrase jamais une valeur deja modifiee par un administrateur")
    void neverOverwritesAnAdminEditedValue() {
        initializer.seedMissingKeys();
        PlatformSettingEntity rate = repository.findBySettingKey("commission_rate").orElseThrow();
        rate.setSettingValue("0.12");
        repository.saveAndFlush(rate);

        initializer.seedMissingKeys();

        assertThat(repository.findBySettingKey("commission_rate").orElseThrow().getSettingValue())
                .isEqualTo("0.12");
    }

    @Test
    @DisplayName("chaque cle porte son type")
    void everyKeyCarriesItsType() {
        initializer.seedMissingKeys();

        assertThat(repository.findBySettingKey("commission_rate").orElseThrow().getValueType())
                .isEqualTo(PlatformSettingType.DECIMAL);
        assertThat(repository.findBySettingKey("urgency_threshold_days").orElseThrow().getValueType())
                .isEqualTo(PlatformSettingType.INTEGER);
        assertThat(repository.findBySettingKey("sms_enabled").orElseThrow().getValueType())
                .isEqualTo(PlatformSettingType.BOOLEAN);
    }

    @Test
    @DisplayName("la valeur amorcee du taux vaut bien le taux global du resolveur")
    void seededRateEqualsGlobalRate() {
        initializer.seedMissingKeys();

        assertThat(new BigDecimal(
                repository.findBySettingKey("commission_rate").orElseThrow().getSettingValue()))
                .isEqualByComparingTo(config.commission().rate());
    }
}
