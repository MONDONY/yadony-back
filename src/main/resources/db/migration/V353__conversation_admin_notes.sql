-- Modération des conversations : notes internes d'un administrateur sur une conversation (jamais
-- visibles des participants). Même rôle que support_ticket_notes (V350) pour les tickets.

CREATE TABLE conversation_admin_notes (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations (id),
    -- Pas de clé étrangère vers admin_users : la suppression d'un compte admin ne doit pas rendre
    -- l'historique d'une conversation illisible (même choix que support_ticket_notes).
    admin_id UUID NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_conversation_admin_notes_conv ON conversation_admin_notes (conversation_id, created_at DESC);

-- Les signalements ouverts sur un message se retrouvent par conversation (reports.target_id) : la
-- file de modération les compte à chaque page. Index partiel sur la seule cible MESSAGE ouverte.
CREATE INDEX IF NOT EXISTS idx_reports_open_message_target
    ON reports (target_id) WHERE target_type = 'MESSAGE' AND status = 'OPEN' AND deleted_at IS NULL;
