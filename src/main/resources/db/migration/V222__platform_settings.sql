-- Lot D — parametres plateforme editables a chaud, sans redeploiement.
--
-- Table volontairement VIDE a la creation. L'amorcage se fait a l'execution
-- (PlatformSettingsInitializer, evenement ApplicationReadyEvent) depuis les properties
-- deja resolues : une migration SQL ne voit ni SMS_ENABLED ni YADONY_COMMISSION_RATE,
-- qui sont des variables d'environnement. Un INSERT en dur ici aurait remis sms_enabled
-- a 'false' en production — c'est-a-dire coupe l'authentification par OTP SMS
-- (SmsService.isEnabled()) des le deploiement.
CREATE TABLE platform_settings (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key    VARCHAR(60)  NOT NULL,
    setting_value  VARCHAR(255) NOT NULL,
    -- DECIMAL | INTEGER | BOOLEAN (PlatformSettingType)
    value_type     VARCHAR(10)  NOT NULL,
    -- admin_users.id de l'auteur de la derniere modification. NULL tant que la ligne
    -- n'a ete ecrite que par l'amorcage.
    updated_by     UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT uq_platform_settings_key UNIQUE (setting_key)
);

COMMENT ON TABLE platform_settings IS
    'Parametres plateforme editables depuis le back-office (Lot D). Lignes jamais supprimees.';
COMMENT ON COLUMN platform_settings.setting_key IS
    'commission_rate | urgency_threshold_days | reimbursement_cap_eur | sms_enabled';
