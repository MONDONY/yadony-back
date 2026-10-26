-- Alerte reportée : cachée de la file « ouvertes » jusqu'à cette date, mais toujours non résolue
-- (la déduplication des schedulers continue donc de s'appliquer).
ALTER TABLE admin_alerts
    ADD COLUMN IF NOT EXISTS snoozed_until TIMESTAMP WITH TIME ZONE;
