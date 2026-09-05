package com.yadony.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * Garnit platform_settings a partir des properties deja resolues, une seule fois, sans
 * jamais ecraser une valeur existante.
 *
 * <p>Le seed vit ici et non dans la migration parce que les valeurs viennent de
 * sources heterogenes : trois sous {@code yadony.*} via {@link YadonyConfigProperties},
 * {@code app.sms.enabled} et {@code yadony.pro.enabled} lues en {@code @Value} — et toutes
 * surchargeables par variable d'environnement, invisible depuis du SQL.
 */
@Component
public class PlatformSettingsInitializer {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingsInitializer.class);

    private final PlatformSettingRepository repository;
    private final YadonyConfigProperties config;
    private final boolean smsEnabledProperty;
    private final boolean proEnabledProperty;
    private final boolean kycDiditEnabledProperty;

    public PlatformSettingsInitializer(PlatformSettingRepository repository,
                                       YadonyConfigProperties config,
                                       @Value("${app.sms.enabled:false}") boolean smsEnabledProperty,
                                       @Value("${yadony.pro.enabled:false}") boolean proEnabledProperty,
                                       @Value("${yadony.kyc.didit.enabled:false}") boolean kycDiditEnabledProperty) {
        this.repository = repository;
        this.config = config;
        this.smsEnabledProperty = smsEnabledProperty;
        this.proEnabledProperty = proEnabledProperty;
        this.kycDiditEnabledProperty = kycDiditEnabledProperty;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        int inserted = seedMissingKeys();
        if (inserted > 0) {
            log.info("[CONFIG] {} parametre(s) plateforme amorce(s) depuis les properties", inserted);
        }
    }

    /** @return le nombre de lignes reellement inserees. Idempotent. */
    @Transactional
    public int seedMissingKeys() {
        Map<PlatformSettingKey, String> defaults = new EnumMap<>(PlatformSettingKey.class);
        defaults.put(PlatformSettingKey.COMMISSION_RATE,
                config.commission().rate().toPlainString());
        defaults.put(PlatformSettingKey.URGENCY_THRESHOLD_DAYS,
                String.valueOf(config.urgency().thresholdDays()));
        defaults.put(PlatformSettingKey.REIMBURSEMENT_CAP_EUR,
                config.reimbursement().maxAmountEur().toPlainString());
        defaults.put(PlatformSettingKey.SMS_ENABLED,
                String.valueOf(smsEnabledProperty));
        defaults.put(PlatformSettingKey.PRO_ENABLED,
                String.valueOf(proEnabledProperty));
        defaults.put(PlatformSettingKey.KYC_DIDIT_ENABLED,
                String.valueOf(kycDiditEnabledProperty));

        int inserted = 0;
        for (Map.Entry<PlatformSettingKey, String> entry : defaults.entrySet()) {
            PlatformSettingKey key = entry.getKey();
            if (repository.findBySettingKey(key.key()).isEmpty()) {
                repository.save(new PlatformSettingEntity(key.key(), entry.getValue(), key.type()));
                inserted++;
            }
        }
        return inserted;
    }
}
