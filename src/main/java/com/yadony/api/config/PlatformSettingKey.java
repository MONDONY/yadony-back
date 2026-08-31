package com.yadony.api.config;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

/**
 * Les parametres plateforme editables.
 *
 * <p>⚠️ {@code URGENCY_THRESHOLD_DAYS} est bien en JOURS : la property historique est
 * {@code yadony.urgency.threshold-days} (defaut 3) et le contrat public expose
 * {@code {"thresholdDays": 3}}. Renommer en heures multiplierait le seuil par 24.
 *
 * <p>{@code PRO_ENABLED} est le feature flag de l'offre PRO : faux, l'application mobile
 * masque toute entree PRO ET le serveur cesse d'appliquer les quotas reserves aux comptes
 * standard (il n'y a plus rien a vendre pour les depasser). Il permet de livrer
 * l'application avant l'ouverture commerciale du PRO, puis d'allumer l'offre depuis le
 * back-office sans redeploiement.
 */
public enum PlatformSettingKey {

    COMMISSION_RATE("commission_rate", PlatformSettingType.DECIMAL),
    URGENCY_THRESHOLD_DAYS("urgency_threshold_days", PlatformSettingType.INTEGER),
    REIMBURSEMENT_CAP_EUR("reimbursement_cap_eur", PlatformSettingType.DECIMAL),
    SMS_ENABLED("sms_enabled", PlatformSettingType.BOOLEAN),
    PRO_ENABLED("pro_enabled", PlatformSettingType.BOOLEAN);

    private final String key;
    private final PlatformSettingType type;

    PlatformSettingKey(String key, PlatformSettingType type) {
        this.key = key;
        this.type = type;
    }

    public String key() {
        return key;
    }

    public PlatformSettingType type() {
        return type;
    }

    public static PlatformSettingKey fromKey(String key) {
        return Arrays.stream(values())
                .filter(k -> k.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "platform-setting-unknown", "Unprocessable Entity",
                        "Parametre plateforme inconnu : " + key));
    }
}
