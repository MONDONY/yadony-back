package com.yadony.api.config;

/** Type de la valeur stockee en texte dans platform_settings.setting_value. */
public enum PlatformSettingType {
    DECIMAL,
    INTEGER,
    BOOLEAN,
    /**
     * Liste d'indicatifs téléphoniques séparés par des virgules (« 221,225 »), vide permise :
     * voir {@link CallingCodeList}.
     */
    CALLING_CODES
}
