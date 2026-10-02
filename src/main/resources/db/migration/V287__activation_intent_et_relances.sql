-- V287 : guidage après le KYC.
-- Intention déclarée (envoyer / voyager / les deux) et pays visé, date de vérification
-- KYC (absente jusqu'ici), garde-fou des relances « première action ».

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS intent VARCHAR(16),
    ADD COLUMN IF NOT EXISTS intent_destination_country VARCHAR(2),
    ADD COLUMN IF NOT EXISTS intent_source VARCHAR(16),
    ADD COLUMN IF NOT EXISTS intent_declared_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS kyc_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS first_action_reminder_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS first_action_reminder_last_at TIMESTAMPTZ;

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_intent;
ALTER TABLE users
    ADD CONSTRAINT chk_users_intent CHECK (intent IS NULL OR intent IN ('SENDER', 'TRAVELER', 'BOTH')) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT chk_users_intent;

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_intent_source;
ALTER TABLE users
    ADD CONSTRAINT chk_users_intent_source
        CHECK (intent_source IS NULL OR intent_source IN ('SIGNUP', 'PROMPT', 'INFERRED', 'SETTINGS')) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT chk_users_intent_source;

-- Date de vérification KYC des comptes déjà vérifiés : décision admin sinon dernière mise à jour.
UPDATE users u
SET kyc_verified_at = COALESCE(k.decided_at, k.updated_at)
FROM kyc_schema.kyc_verifications k
WHERE k.user_id = u.id
  AND k.status = 'VERIFIED'
  AND u.kyc_status = 'VERIFIED'
  AND u.kyc_verified_at IS NULL;

-- Intention déduite de l'activité passée.
UPDATE users u
SET intent = CASE
        WHEN EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id AND a.deleted_at IS NULL)
         AND (EXISTS (SELECT 1 FROM package_requests p WHERE p.sender_id = u.id AND p.deleted_at IS NULL)
              OR EXISTS (SELECT 1 FROM bids b WHERE b.sender_id = u.id AND b.deleted_at IS NULL))
            THEN 'BOTH'
        WHEN EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id AND a.deleted_at IS NULL)
            THEN 'TRAVELER'
        ELSE 'SENDER'
    END,
    intent_source = 'INFERRED',
    intent_declared_at = now()
WHERE u.intent IS NULL
  AND (EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id AND a.deleted_at IS NULL)
       OR EXISTS (SELECT 1 FROM package_requests p WHERE p.sender_id = u.id AND p.deleted_at IS NULL)
       OR EXISTS (SELECT 1 FROM bids b WHERE b.sender_id = u.id AND b.deleted_at IS NULL));

-- Pays visé déduit : arrivée du trajet publié ou du trajet réservé le plus récent.
UPDATE users u
SET intent_destination_country = (
    SELECT x.cc FROM (
        SELECT a.arrival_country_code AS cc, a.created_at AS at
        FROM announcements a
        WHERE a.traveler_id = u.id AND a.deleted_at IS NULL AND a.arrival_country_code IS NOT NULL
        UNION ALL
        SELECT a.arrival_country_code AS cc, b.created_at AS at
        FROM bids b JOIN announcements a ON a.id = b.announcement_id
        WHERE b.sender_id = u.id AND b.deleted_at IS NULL AND a.arrival_country_code IS NOT NULL
    ) x
    ORDER BY x.at DESC
    LIMIT 1)
WHERE u.intent_source = 'INFERRED'
  AND u.intent_destination_country IS NULL;
