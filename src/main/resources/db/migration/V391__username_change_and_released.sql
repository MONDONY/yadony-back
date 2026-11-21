-- Changement d'identifiant Yadony : date du dernier changement, unicité insensible à la casse,
-- et réservation des anciens identifiants (anti-usurpation).

ALTER TABLE users ADD COLUMN IF NOT EXISTS username_changed_at TIMESTAMPTZ;

-- Contrôle à passer AVANT déploiement (doit renvoyer 0 ligne, sinon la création de l'index échoue) :
--   SELECT lower(username), count(*) FROM users GROUP BY lower(username) HAVING count(*) > 1;
-- Les identifiants générés (« user<epoch>... », 14 à 26 caractères) sont en minuscules : aucun doublon attendu.
-- L'index porte sur toutes les lignes, soft-deleted comprises, comme ux_users_username (V183).
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username_lower ON users (lower(username));

-- Ancien identifiant libéré par un changement : réservé le temps du délai de grâce.
-- Purge physique voulue (table de réservation à durée de vie limitée, aucune donnée à conserver).
CREATE TABLE IF NOT EXISTS released_usernames (
    username       VARCHAR(32)  PRIMARY KEY,
    released_at    TIMESTAMPTZ  NOT NULL,
    former_user_id UUID         NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_released_usernames_released_at ON released_usernames (released_at);
