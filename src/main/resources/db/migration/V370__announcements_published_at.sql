-- V370 : date de première publication d'un trajet, pour la limite mensuelle de trajets.
--
-- La date de création ne convient pas (un brouillon créé un mois et publié le suivant échappait au quota) et la
-- dernière mise à jour non plus (une modification ou une offre acceptée ferait recompter un trajet ancien).
-- published_at est la date de la publication la plus récente faite dans un nouveau mois : posée à la création d'un
-- trajet non brouillon ou au passage BROUILLON vers ACTIF, inchangée quand un trajet dépublié est republié dans le
-- même mois, remise à la date du jour quand il l'est un mois plus tard. NULL = jamais publié (brouillon).
--
-- Rattrapage : un trajet existant qui n'est pas un brouillon est réputé publié à sa création.

ALTER TABLE announcements ADD COLUMN IF NOT EXISTS published_at TIMESTAMP WITH TIME ZONE;

UPDATE announcements SET published_at = created_at WHERE status <> 'DRAFT' AND published_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_announcements_traveler_published_at
    ON announcements (traveler_id, published_at);
