-- Purge des messages 3 jours après la livraison du colis : date à laquelle les messages et
-- les photos de la conversation ont été effacés. Le document Firestore est conservé (vide).
ALTER TABLE conversations
    ADD COLUMN messages_purged_at TIMESTAMPTZ;

CREATE INDEX idx_conversations_messages_not_purged
    ON conversations (bid_id)
    WHERE messages_purged_at IS NULL;
