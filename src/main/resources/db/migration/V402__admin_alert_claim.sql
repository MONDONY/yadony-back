-- Prise en charge d'une alerte (PWA admin mobile) : simple marqueur « je m'en occupe »,
-- sans effet sur resolved / snoozed_until. Pas de clé étrangère (même choix que admin_devices).
ALTER TABLE admin_alerts
    ADD COLUMN claimed_by UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ;
