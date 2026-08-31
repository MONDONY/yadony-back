package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PlatformSettingsServiceIT — lecture cachee, ecriture bornee, audit")
class PlatformSettingsServiceIT {

    @Autowired PlatformSettingsService service;
    @Autowired PlatformSettingRepository repository;
    @Autowired PlatformSettingsInitializer initializer;
    @Autowired PlatformSettingsCache cache;
    @Autowired CacheManager cacheManager;
    @Autowired YadonyConfigProperties config;

    @MockitoBean AuditService auditService;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    /**
     * ⚠️ @AfterEach autant que @BeforeEach : la H2 de test est PARTAGEE entre contextes
     * ({@code DB_CLOSE_DELAY=-1}) et l'amorcage ne rejoue pas. Laisser {@code sms_enabled=true}
     * derriere soi ferait echouer {@code ConfigControllerSmsEnabledTest}, qui attend false.
     */
    @BeforeEach
    @AfterEach
    void reset() {
        repository.deleteAll();
        cache.evict();
        initializer.seedMissingKeys();
        cache.evict();
    }

    private static Map<PlatformSettingKey, String> change(PlatformSettingKey key, String value) {
        Map<PlatformSettingKey, String> changes = new EnumMap<>(PlatformSettingKey.class);
        changes.put(key, value);
        return changes;
    }

    @Test
    @DisplayName("les getters typent la valeur stockee en texte")
    void typedGettersParseStoredText() {
        assertThat(service.commissionRate()).isEqualByComparingTo(config.commission().rate());
        assertThat(service.urgencyThresholdDays()).isEqualTo(config.urgency().thresholdDays());
        assertThat(service.reimbursementCapEur())
                .isEqualByComparingTo(config.reimbursement().maxAmountEur());
        assertThat(service.smsEnabled()).isFalse();
        assertThat(service.proEnabled()).isFalse();
    }

    @Test
    @DisplayName("une cle absente retombe sur la property, jamais sur une exception")
    void missingRowFallsBackToTheProperty() {
        repository.deleteAll();
        cache.evict();

        assertThat(service.commissionRate()).isEqualByComparingTo(config.commission().rate());
        assertThat(service.smsEnabled()).isFalse();
        assertThat(service.proEnabled()).isFalse();
    }

    @Test
    @DisplayName("offre PRO fermee : tout compte beneficie des quotas PRO ; ouverte : seuls les comptes PRO")
    void proQuotasFollowTheProFlag() {
        // Offre fermee (defaut) : un compte standard n'a pas a se heurter a un quota qu'il
        // ne peut pas acheter.
        assertThat(service.hasProQuotas(false)).isTrue();
        assertThat(service.hasProQuotas(true)).isTrue();

        service.update(change(PlatformSettingKey.PRO_ENABLED, "true"), ADMIN_ID);

        assertThat(service.proEnabled()).isTrue();
        assertThat(service.hasProQuotas(false)).isFalse();
        assertThat(service.hasProQuotas(true)).isTrue();
    }

    @Test
    @DisplayName("la vue par cle sert les cinq reglages dans l'ordre de l'enum, meme sans aucune ligne")
    void listByKeyServesEveryKeyEvenWithoutRows() {
        // Base restauree d'avant l'amorcage : l'ecran admin doit rester complet, chaque
        // cle portee par sa valeur effective (celle du contrat public), sans auteur.
        repository.deleteAll();
        cache.evict();

        List<PlatformSettingView> views = service.listByKey();

        assertThat(views).extracting(PlatformSettingView::key)
                .containsExactly(PlatformSettingKey.values());
        PlatformSettingView pro = views.get(views.size() - 1);
        assertThat(pro.key()).isEqualTo(PlatformSettingKey.PRO_ENABLED);
        assertThat(pro.value()).isEqualTo("false");
        assertThat(pro.updatedAt()).isNull();
        assertThat(pro.updatedBy()).isNull();
    }

    @Test
    @DisplayName("la vue par cle expose la date et l'auteur d'un reglage modifie, et rien pour les autres")
    void listByKeyExposesEditorOnlyForEditedRows() {
        service.update(change(PlatformSettingKey.PRO_ENABLED, "true"), ADMIN_ID);

        List<PlatformSettingView> views = service.listByKey();

        PlatformSettingView pro = views.stream()
                .filter(v -> v.key() == PlatformSettingKey.PRO_ENABLED).findFirst().orElseThrow();
        assertThat(pro.value()).isEqualTo("true");
        assertThat(pro.updatedBy()).isEqualTo(ADMIN_ID);
        assertThat(pro.updatedAt()).isNotNull();
        // Amorce mais jamais modifie : BaseEntity l'horodate, la vue ne doit pas le montrer
        // comme une modification.
        PlatformSettingView sms = views.stream()
                .filter(v -> v.key() == PlatformSettingKey.SMS_ENABLED).findFirst().orElseThrow();
        assertThat(sms.updatedAt()).isNull();
        assertThat(sms.updatedBy()).isNull();
    }

    @Test
    @DisplayName("pro_enabled n'accepte que true ou false → 422 sinon")
    void proEnabledRejectsAnythingButABoolean() {
        assertThatThrownBy(() ->
                service.update(change(PlatformSettingKey.PRO_ENABLED, "oui"), ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("true ou false");
        assertThat(service.proEnabled()).isFalse();
    }

    @Test
    @DisplayName("l'ecriture est visible immediatement : le cache est evince")
    void writeEvictsTheCache() {
        service.commissionRate();
        assertThat(cacheManager.getCache("platform-settings")).isNotNull();

        service.update(change(PlatformSettingKey.COMMISSION_RATE, "0.12"), ADMIN_ID);

        assertThat(service.commissionRate()).isEqualByComparingTo(new BigDecimal("0.12"));
    }

    @Test
    @DisplayName("chaque cle modifiee produit une entree audit_log avec ancienne et nouvelle valeur")
    void eachChangeIsAudited() {
        service.update(change(PlatformSettingKey.SMS_ENABLED, "true"), ADMIN_ID);

        verify(auditService).log(eq("platform_setting"), any(UUID.class),
                eq("PLATFORM_SETTING_CHANGED"), eq(ADMIN_ID), any(Map.class));
    }

    @Test
    @DisplayName("une valeur identique n'est ni ecrite ni auditee")
    void unchangedValueIsNotAudited() {
        String current = config.commission().rate().toPlainString();

        service.update(change(PlatformSettingKey.COMMISSION_RATE, current), ADMIN_ID);

        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("taux de commission au-dela de 30 % → 422")
    void commissionRateAbove30PercentIsRejected() {
        assertThatThrownBy(() ->
                service.update(change(PlatformSettingKey.COMMISSION_RATE, "0.31"), ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("0 et 30");
    }

    @Test
    @DisplayName("taux de commission negatif → 422")
    void negativeCommissionRateIsRejected() {
        assertThatThrownBy(() ->
                service.update(change(PlatformSettingKey.COMMISSION_RATE, "-0.01"), ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("plafond de remboursement au-dela de 500 € → 422")
    void reimbursementCapAbove500IsRejected() {
        assertThatThrownBy(() ->
                service.update(change(PlatformSettingKey.REIMBURSEMENT_CAP_EUR, "501"), ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("seuil d'urgence hors de 1..30 jours → 422")
    void urgencyThresholdOutOfRangeIsRejected() {
        assertThatThrownBy(() ->
                service.update(change(PlatformSettingKey.URGENCY_THRESHOLD_DAYS, "0"), ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("1 et 30");
    }

    @Test
    @DisplayName("valeur non numerique → 422, pas 500")
    void nonNumericValueIsRejectedAs422() {
        assertThatThrownBy(() ->
                service.update(change(PlatformSettingKey.COMMISSION_RATE, "beaucoup"), ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("le cliche porte l'auteur et la date de la derniere modification")
    void snapshotCarriesLastEditor() {
        service.update(change(PlatformSettingKey.SMS_ENABLED, "true"), ADMIN_ID);

        PlatformSettingsSnapshot snapshot = service.snapshot();

        assertThat(snapshot.smsEnabled()).isTrue();
        assertThat(snapshot.proEnabled()).isFalse();
        assertThat(snapshot.updatedBy()).isEqualTo(ADMIN_ID);
        assertThat(snapshot.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("une modification refusee ne laisse aucune trace")
    void rejectedUpdateChangesNothing() {
        BigDecimal before = service.commissionRate();

        assertThatThrownBy(() ->
                service.update(change(PlatformSettingKey.COMMISSION_RATE, "0.99"), ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class);

        assertThat(service.commissionRate()).isEqualByComparingTo(before);
    }
}
