-- Appels audio in-app (Stream Video).
-- 1. bids.delivered_at : date de confirmation de livraison, pour la fenêtre d'appel
--    « jusqu'à 3 jours après la livraison ». Rétro-remplie avec updated_at pour les
--    commandes déjà livrées (approximation : la fenêtre se ferme au pire plus tôt).
-- 2. calls : un appel = une ligne, statut piloté par les webhooks Stream.
ALTER TABLE bids ADD COLUMN delivered_at TIMESTAMP;
UPDATE bids SET delivered_at = updated_at WHERE status = 'COMPLETED' AND delivered_at IS NULL;

CREATE TABLE calls (
    id               UUID PRIMARY KEY,
    conversation_id  UUID NOT NULL REFERENCES conversations(id),
    bid_id           UUID NOT NULL REFERENCES bids(id),
    caller_id        UUID NOT NULL REFERENCES users(id),
    callee_id        UUID NOT NULL REFERENCES users(id),
    stream_call_id   VARCHAR(100) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    started_at       TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,
    duration_s       INTEGER,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL,
    deleted_at       TIMESTAMP,
    CONSTRAINT uq_calls_stream_call_id UNIQUE (stream_call_id),
    CONSTRAINT chk_calls_status CHECK (status IN ('RINGING', 'ANSWERED', 'ENDED', 'MISSED', 'REJECTED'))
);
CREATE INDEX idx_calls_conversation ON calls (conversation_id);
CREATE INDEX idx_calls_bid ON calls (bid_id);
