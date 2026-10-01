-- Lot 3C : conversation séparée voyageur ↔ destinataire.
--
-- Une conversation porte désormais un type. RECIPIENT_TRAVELER réutilise les colonnes
-- existantes : sender_id y désigne le DESTINATAIRE (participant A), traveler_id le
-- voyageur, pour que la Cloud Function des non-lus et les règles Firestore, qui ne
-- connaissent que senderId/travelerId, restent inchangées.
ALTER TABLE conversations
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'SENDER_TRAVELER';

ALTER TABLE conversations
    ADD CONSTRAINT chk_conversations_kind
    CHECK (kind IN ('SENDER_TRAVELER', 'RECIPIENT_TRAVELER'));

-- Fermeture d'une conversation destinataire quand l'expéditeur change de destinataire :
-- l'ancien destinataire perd l'accès, le voyageur garde l'historique en lecture seule.
ALTER TABLE conversations
    ADD COLUMN closed_at TIMESTAMPTZ;

-- Unicité par (bid, type) et non plus par bid seul. Une conversation fermée ne compte
-- pas : le nouveau destinataire confirmé en ouvre une nouvelle sur le même colis.
DROP INDEX IF EXISTS conversations_bid_id_active_idx;

CREATE UNIQUE INDEX conversations_bid_kind_active_idx
    ON conversations (bid_id, kind)
    WHERE deleted_at IS NULL AND closed_at IS NULL;
