-- V365 : relances de vérification d'identité (push + e-mail à J+1 / J+3 / J+7).
-- L'ancre est la date du dernier geste de l'utilisateur dans le parcours ; le compteur et
-- la date du dernier envoi servent de garde-fou, la désinscription est un horodatage.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS kyc_reminder_anchor_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS kyc_reminder_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS kyc_reminder_last_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS kyc_reminders_opted_out_at TIMESTAMPTZ;

-- Stock initial : comptes non vérifiés ayant ouvert une session dans les 90 derniers jours.
UPDATE users u SET kyc_reminder_anchor_at = NOW()
FROM kyc_schema.kyc_verifications k
WHERE k.user_id = u.id AND k.deleted_at IS NULL AND k.verification_session_id IS NOT NULL
  AND k.updated_at >= NOW() - INTERVAL '90 days'
  AND u.deleted_at IS NULL AND u.kyc_status <> 'VERIFIED';

CREATE INDEX IF NOT EXISTS idx_users_kyc_reminder_due ON users (kyc_reminder_anchor_at)
    WHERE kyc_reminder_anchor_at IS NOT NULL AND kyc_reminders_opted_out_at IS NULL AND kyc_reminder_count < 3;
