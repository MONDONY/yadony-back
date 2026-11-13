-- Messagerie avant réservation : conversation de type INQUIRY (question d'un demandeur au
-- propriétaire d'une annonce, sans bid).
--
-- INQUIRY réutilise les colonnes historiques : sender_id = le DEMANDEUR (asker), traveler_id =
-- le propriétaire de l'annonce. Pour que la Cloud Function des non-lus et les règles Firestore
-- (qui ne connaissent que senderId/travelerId) restent inchangées.
-- La conversation naît sans bid : bid_id devient nullable. Elle est « promue » (bid_id rempli,
-- kind SENDER_TRAVELER, quota levé) quand un bid est créé, et re-verrouillée si ce bid est annulé.
ALTER TABLE conversations ALTER COLUMN bid_id DROP NOT NULL;

ALTER TABLE conversations
    ADD COLUMN announcement_id          UUID REFERENCES announcements(id),
    ADD COLUMN asker_id                 UUID REFERENCES users(id),
    ADD COLUMN quota_lifted_at          TIMESTAMPTZ,
    ADD COLUMN inquiry_first_message_at TIMESTAMPTZ,
    ADD COLUMN last_activity_at         TIMESTAMPTZ;

ALTER TABLE conversations DROP CONSTRAINT chk_conversations_kind;
ALTER TABLE conversations
    ADD CONSTRAINT chk_conversations_kind
    CHECK (kind IN ('SENDER_TRAVELER', 'RECIPIENT_TRAVELER', 'INQUIRY'));

ALTER TABLE conversations
    ADD CONSTRAINT chk_conversations_inquiry_refs
    CHECK (kind <> 'INQUIRY' OR (asker_id IS NOT NULL AND announcement_id IS NOT NULL));

-- Une seule conversation INQUIRY ouverte par couple (demandeur, annonce).
CREATE UNIQUE INDEX conversations_inquiry_active_idx
    ON conversations (asker_id, announcement_id)
    WHERE kind = 'INQUIRY' AND deleted_at IS NULL AND closed_at IS NULL;
