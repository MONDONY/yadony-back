-- V370 : lot 2b, GP Pro = professionnel (un seul badge) et limite mensuelle de trajets.
--
-- users.monthly_trip_limit : nombre maximal de trajets publiés par mois civil pour CET utilisateur,
-- réglé dans l'admin. NULL = aucune limite (aucune valeur par défaut). Un GP Pro validé n'est jamais
-- limité, quelle que soit la valeur saisie.
--
-- gp_pro_applications : le formulaire GP Pro porte désormais le numéro d'immatriculation (chiffré au
-- repos par EncryptedStringConverter, d'où la largeur 255) et le pays. Nullables : une demande venue
-- d'une ancienne app n'en porte pas, et l'approbation ne touche alors pas au statut du voyageur.

ALTER TABLE users ADD COLUMN IF NOT EXISTS monthly_trip_limit INTEGER;

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_monthly_trip_limit;
ALTER TABLE users
    ADD CONSTRAINT chk_users_monthly_trip_limit
        CHECK (monthly_trip_limit IS NULL OR monthly_trip_limit BETWEEN 1 AND 100) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT chk_users_monthly_trip_limit;

ALTER TABLE gp_pro_applications ADD COLUMN IF NOT EXISTS business_registration_number VARCHAR(255);
ALTER TABLE gp_pro_applications ADD COLUMN IF NOT EXISTS business_country VARCHAR(2);
