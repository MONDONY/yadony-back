-- V378 : DAC7, collecte ciblée (tâche 1 du plan du 11/11/2026).
--
-- users.tax_birth_place : lieu de naissance du voyageur résident de l'Union, demandé SEULEMENT quand il n'a pas de
-- numéro fiscal (BOI-INT-AEA-30-20-20 § 110), effacé dès qu'un NIF est enregistré. Chiffré au repos par
-- EncryptedStringConverter (AES-256-GCM, IV aléatoire, base64) comme le NIF : d'où la largeur 512 pour 120 caractères
-- saisis (accents compris). Jamais dans un WHERE, un ORDER BY, un UNIQUE, un journal ni un payload d'audit.
-- La date de naissance et l'adresse ne sont pas recopiées : ce sont les colonnes du profil (birth_date,
-- residence_street, residence_line2, residence_postal_code, city), déjà lues par Dac7IdentityResolver.
ALTER TABLE users ADD COLUMN IF NOT EXISTS tax_birth_place VARCHAR(512);

-- Registre des diligences : d'où vient une information fournie. USER = saisie du voyageur ; DIDIT = reprise de la
-- pièce d'identité vérifiée, avec l'identifiant de la session de vérification comme preuve de fiabilité (CGI 1649 ter
-- D). Nuls pour les lignes antérieures et pour les démarches (demande, rappels, décision). Ajout de colonnes nullables
-- sans valeur par défaut : aucune ligne n'est réécrite, le trigger d'immuabilité de V376 n'est pas sollicité.
ALTER TABLE dac7_due_diligence_log ADD COLUMN IF NOT EXISTS source VARCHAR(10);
ALTER TABLE dac7_due_diligence_log ADD COLUMN IF NOT EXISTS verification_ref VARCHAR(255);
ALTER TABLE dac7_due_diligence_log DROP CONSTRAINT IF EXISTS chk_dac7_due_diligence_log_source;
ALTER TABLE dac7_due_diligence_log ADD CONSTRAINT chk_dac7_due_diligence_log_source
    CHECK (source IS NULL OR source IN ('USER', 'DIDIT'));
