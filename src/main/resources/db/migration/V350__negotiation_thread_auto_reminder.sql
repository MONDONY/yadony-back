-- V350 : horodatage du rappel AUTOMATIQUE envoyé à la partie qui doit répondre dans un fil de
-- négociation resté sans réponse (distinct de last_nudge_at, la relance faite à la main par
-- l'autre partie). NULL = aucun rappel automatique. Un nouveau message remet le fil en
-- attente d'un rappel : on compare cet horodatage à last_activity_at.
ALTER TABLE negotiation_threads
    ADD COLUMN IF NOT EXISTS auto_reminder_at TIMESTAMP NULL;
