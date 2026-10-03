-- V289 : un utilisateur peut retirer un ancien ticket de sa boîte support
-- (FLUTTER-9W). Jamais une suppression : le ticket reste entier pour le
-- back-office et l'export de données, seule la liste de l'utilisateur
-- l'écarte. Nullable : NULL = visible.

ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS hidden_by_user_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_support_tickets_user_visible
    ON support_tickets (user_id, last_message_at DESC)
    WHERE hidden_by_user_at IS NULL;
