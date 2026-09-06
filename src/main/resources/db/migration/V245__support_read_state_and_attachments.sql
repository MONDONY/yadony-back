-- Etat de lecture cote utilisateur. NULL = jamais ouvert, donc tous les
-- messages admin comptent comme non lus. Distincte du statut du ticket : lire
-- n'est pas repondre, un WAITING_USER lu reste WAITING_USER.
ALTER TABLE support_tickets ADD COLUMN user_last_read_at TIMESTAMP;

-- Pieces jointes images d'un message, quatre au maximum (plafond applique en
-- Java : une contrainte SQL sur un COUNT couterait un trigger pour rien).
CREATE TABLE support_message_attachments (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES support_messages (id),
    -- Cle d'objet R2. Jamais exposee telle quelle : l'API rend une URL
    -- presignee d'une heure, une photo de justificatif ne doit pas etre
    -- devinable par quiconque connait la cle.
    object_key TEXT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_support_message_attachments_message
    ON support_message_attachments (message_id);
