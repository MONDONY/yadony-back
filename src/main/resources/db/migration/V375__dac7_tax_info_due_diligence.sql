-- V375 : DAC7, lot 3a. Données fiscales du voyageur et registre des diligences.
--
-- users : numéro d'identification fiscale (NIF), pays d'émission du NIF et numéro de TVA facultatif, chiffrés au
-- repos par EncryptedStringConverter (AES-256-GCM, comme pro_siret : d'où la largeur 255). Jamais dans un WHERE,
-- un ORDER BY, un UNIQUE, un journal ni un payload d'audit. Le pays de RÉSIDENCE fiscale n'est pas recopié ici :
-- il vient de users.country (ou du pays de l'entreprise pour un professionnel), déjà tenu par le profil.
-- Suivi de la demande (posé par le lot 3b) : date de la demande, nombre de rappels, date de la réponse.
ALTER TABLE users ADD COLUMN IF NOT EXISTS tax_identification_number VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS tax_country VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS vat_number VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS tax_info_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS tax_reminder_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS tax_info_provided_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_tax_reminder_count;
ALTER TABLE users ADD CONSTRAINT chk_users_tax_reminder_count CHECK (tax_reminder_count >= 0) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT chk_users_tax_reminder_count;

-- Registre des démarches de diligence DAC7 (annexe III au CGI, art. 344 G quindecies) : demande, rappels, réponse,
-- décision de l'équipe. En ajout seul, comme audit_log : un trigger refuse toute modification et toute suppression.
-- La note est libre mais ne porte jamais de donnée sensible (ni NIF, ni numéro de TVA, ni adresse).
-- Pas de clé étrangère vers users, comme audit_log : le registre doit survivre à toute opération sur le compte.
CREATE TABLE dac7_due_diligence_log (
    id         BIGSERIAL                NOT NULL,
    user_id    UUID                     NOT NULL,
    action     VARCHAR(20)              NOT NULL,
    channel    VARCHAR(20)              NOT NULL,
    -- Auteur : nul pour le système et pour le voyageur lui-même, identifiant de l'administrateur pour une décision.
    actor_id   UUID,
    note       VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_dac7_due_diligence_log PRIMARY KEY (id),
    CONSTRAINT chk_dac7_due_diligence_log_action
        CHECK (action IN ('REQUEST', 'REMINDER_1', 'REMINDER_2', 'PROVIDED', 'TEAM_DECISION')),
    CONSTRAINT chk_dac7_due_diligence_log_channel
        CHECK (channel IN ('IN_APP', 'PUSH', 'EMAIL', 'SMS', 'ADMIN'))
);

CREATE INDEX idx_dac7_due_diligence_log_user ON dac7_due_diligence_log (user_id, created_at);

CREATE OR REPLACE FUNCTION prevent_dac7_due_diligence_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Le registre des diligences DAC7 est immuable — opération % interdite', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER dac7_due_diligence_log_immutable
    BEFORE UPDATE OR DELETE ON dac7_due_diligence_log
    FOR EACH ROW EXECUTE FUNCTION prevent_dac7_due_diligence_log_modification();

COMMENT ON TABLE dac7_due_diligence_log IS
    'Registre DAC7 des diligences (demande, rappels, réponse, décision équipe). Immuable, sans donnée sensible.';
