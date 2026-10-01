-- Lot 2 destinataire : rattachement d'un colis au compte Yadony de son destinataire.
-- Quand bids.recipient_phone correspond au numéro d'un compte, le colis lui est
-- proposé (PENDING) ; le destinataire confirme qu'il est pour lui (CONFIRMED) et
-- suit alors le colis dans l'app, code de retrait compris, ou le refuse (DECLINED).
-- Un seul lien par colis, jamais supprimé physiquement (soft delete BaseEntity).

CREATE TABLE bid_recipient_links (
    id                 UUID PRIMARY KEY,
    bid_id             UUID NOT NULL REFERENCES bids(id),
    recipient_user_id  UUID NOT NULL REFERENCES users(id),
    status             VARCHAR(20) NOT NULL,
    responded_at       TIMESTAMPTZ,
    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL,
    deleted_at         TIMESTAMP,
    CONSTRAINT uq_bid_recipient_links_bid UNIQUE (bid_id),
    CONSTRAINT chk_bid_recipient_links_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'DECLINED'))
);

CREATE INDEX idx_bid_recipient_links_recipient ON bid_recipient_links (recipient_user_id);
