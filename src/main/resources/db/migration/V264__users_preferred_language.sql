-- V264 : langue préférée de l'utilisateur (i18n anglais).
--
-- Le serveur rédige dans cette langue ce qu'il envoie hors de la requête de
-- l'utilisateur : notifications push et du centre de notifications, SMS de
-- secours. 'fr' par défaut : tous les comptes existants reçoivent déjà du
-- français, rien ne change pour eux. L'app la met à jour par
-- PATCH /users/me/preferences.

ALTER TABLE users ADD COLUMN IF NOT EXISTS preferred_language VARCHAR(2) NOT NULL DEFAULT 'fr';

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_preferred_language;
ALTER TABLE users
    ADD CONSTRAINT chk_users_preferred_language CHECK (preferred_language IN ('fr', 'en'));
