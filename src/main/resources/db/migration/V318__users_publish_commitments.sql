-- Engagements de publication : avant sa première publication de trajet (voyageur)
-- ou de demande d'envoi (expéditeur), l'utilisateur lit ses engagements et coche
-- « J'ai lu et pris connaissance ». Version acceptée et date, par rôle. Null = jamais
-- accepté. Une nouvelle version du texte redemande l'acceptation ; la preuve de chaque
-- acceptation est dans audit_log (PUBLISH_COMMITMENT_ACCEPTED).
ALTER TABLE users ADD COLUMN traveler_commitment_version VARCHAR(32);
ALTER TABLE users ADD COLUMN traveler_commitment_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN sender_commitment_version VARCHAR(32);
ALTER TABLE users ADD COLUMN sender_commitment_at TIMESTAMPTZ;
