-- Réglage sms_blocked_country_codes (type CALLING_CODES) : indicatifs vers lesquels aucun SMS
-- ne part. Les SMS Twilio vers l'Afrique étaient facturés et marqués « Delivered » sans jamais
-- arriver (08/11/2026).
--
-- Aucune ligne insérée ici, comme en V222 : PlatformSettingsInitializer l'amorce au démarrage
-- depuis YADONY_SMS_BLOCKED_COUNTRY_CODES (par défaut les indicatifs africains), et le service
-- lit la liste par défaut tant que la ligne manque.
--
-- value_type : « CALLING_CODES » fait 13 caractères, la colonne en acceptait 10.
-- setting_value : 60 indicatifs de 4 chiffres et leurs virgules font 299 caractères.
ALTER TABLE platform_settings ALTER COLUMN value_type TYPE VARCHAR(20);
ALTER TABLE platform_settings ALTER COLUMN setting_value TYPE VARCHAR(500);

COMMENT ON COLUMN platform_settings.value_type IS
    'DECIMAL | INTEGER | BOOLEAN | CALLING_CODES (PlatformSettingType)';
