-- Dernière consultation des correspondances d'une alerte par son propriétaire.
-- NULL = jamais consultée : toutes les correspondances courantes comptent alors
-- comme « nouvelles ». Distinct de last_notified_at, qui borne le digest.
ALTER TABLE corridor_alerts
    ADD COLUMN last_seen_at TIMESTAMP WITH TIME ZONE;
