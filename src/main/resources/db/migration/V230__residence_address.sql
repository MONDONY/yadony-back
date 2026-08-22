-- V230__residence_address.sql
-- Adresse de résidence du voyageur, collectée une seule fois à l'étape 4 de
-- l'onboarding et transmise à Stripe Connect. Distincte de pickup_addresses
-- (points de retrait de colis) : un point de retrait n'est pas un domicile légal.
--
-- Le pays n'est pas dupliqué : users.country, figé à l'étape 2, fait foi.
--
-- Toutes les colonnes sont NULLABLE : l'étape est passable, et une colonne
-- NOT NULL casserait V89MigrationTest dont l'INSERT ne les connaît pas.
ALTER TABLE users
    ADD COLUMN residence_street      VARCHAR(255),
    ADD COLUMN residence_line2       VARCHAR(100),
    ADD COLUMN residence_postal_code VARCHAR(20),
    ADD COLUMN onboarding_seen_at    TIMESTAMPTZ;

COMMENT ON COLUMN users.onboarding_seen_at IS
    'Posé quand l''utilisateur atteint l''accueil depuis le parcours d''onboarding, '
    'qu''il ait tout complété ou tout passé. NULL = le parcours s''impose encore.';
