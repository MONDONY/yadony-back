-- Compte Yadony désigné comme destinataire d'un colis (par identifiant), en plus du nom et du téléphone.
-- Nullable : tous les colis existants et ceux dont le destinataire est saisi à la main restent sans compte.
-- Pas de clé étrangère (même choix que recipient_id des invitations) ; index simple pour retrouver les colis
-- adressés à un compte.
ALTER TABLE bids ADD COLUMN IF NOT EXISTS recipient_user_id UUID;

CREATE INDEX IF NOT EXISTS ix_bids_recipient_user_id ON bids (recipient_user_id);
