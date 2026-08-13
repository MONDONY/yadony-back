-- Composite indexes for the bounded read paths used by the mobile clients.
-- Partial predicates keep soft-deleted rows out of the hot indexes.

CREATE INDEX IF NOT EXISTS idx_bids_announcement_visible_created
    ON bids (announcement_id, created_at DESC)
    WHERE deleted_at IS NULL AND deleted_by_traveler = FALSE;

CREATE INDEX IF NOT EXISTS idx_bids_sender_visible_created
    ON bids (sender_id, created_at DESC)
    WHERE deleted_at IS NULL AND deleted_by_sender = FALSE;

CREATE INDEX IF NOT EXISTS idx_conversations_sender_active_updated
    ON conversations (sender_id, updated_at DESC)
    WHERE sender_deleted_at IS NULL AND sender_archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_traveler_active_updated
    ON conversations (traveler_id, updated_at DESC)
    WHERE traveler_deleted_at IS NULL AND traveler_archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_sender_archived_updated
    ON conversations (sender_id, updated_at DESC)
    WHERE sender_deleted_at IS NULL AND sender_archived_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_traveler_archived_updated
    ON conversations (traveler_id, updated_at DESC)
    WHERE traveler_deleted_at IS NULL AND traveler_archived_at IS NOT NULL;
