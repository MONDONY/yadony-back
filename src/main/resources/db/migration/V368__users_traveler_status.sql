-- V368 : statut légal du voyageur (lot 2 de la conformité intermédiaire).
--
-- Le voyageur déclare s'il voyage comme particulier (INDIVIDUAL) ou comme professionnel
-- (PROFESSIONAL). Un professionnel renseigne sa raison sociale, son numéro d'immatriculation
-- (chiffré au repos par EncryptedStringConverter, d'où la largeur 255) et son pays.
-- NULL = pas encore déclaré : les comptes existants le restent.
--
-- Sans rapport avec is_pro_account / pro_company_name / pro_siret (abonnement PRO, désactivé).

ALTER TABLE users ADD COLUMN IF NOT EXISTS traveler_status VARCHAR(15);
ALTER TABLE users ADD COLUMN IF NOT EXISTS traveler_status_declared_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS traveler_status_terms_version VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS business_name VARCHAR(120);
ALTER TABLE users ADD COLUMN IF NOT EXISTS business_registration_number VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS business_country VARCHAR(2);

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_traveler_status;
ALTER TABLE users
    ADD CONSTRAINT chk_users_traveler_status
        CHECK (traveler_status IN ('INDIVIDUAL', 'PROFESSIONAL')) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT chk_users_traveler_status;

-- PROFESSIONAL : les trois champs business_* sont renseignés.
-- INDIVIDUAL ou non déclaré : aucun des trois.
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_traveler_status_pro_fields;
ALTER TABLE users
    ADD CONSTRAINT chk_users_traveler_status_pro_fields CHECK (
        (traveler_status = 'PROFESSIONAL'
            AND business_name IS NOT NULL
            AND business_registration_number IS NOT NULL
            AND business_country IS NOT NULL)
        OR
        ((traveler_status IS NULL OR traveler_status = 'INDIVIDUAL')
            AND business_name IS NULL
            AND business_registration_number IS NULL
            AND business_country IS NULL)
    ) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT chk_users_traveler_status_pro_fields;
