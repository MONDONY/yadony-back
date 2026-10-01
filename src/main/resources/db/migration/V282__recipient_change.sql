-- Lot 3A : l'expéditeur change de destinataire en cours de route.
--
-- 1. Un lien destinataire soft-deleted (ancien destinataire) ne doit plus bloquer le
--    rattachement du nouveau numéro : la contrainte UNIQUE (bid_id) devient un index
--    unique partiel sur les seuls liens actifs.
ALTER TABLE bid_recipient_links DROP CONSTRAINT uq_bid_recipient_links_bid;

CREATE UNIQUE INDEX uq_bid_recipient_links_bid_active
    ON bid_recipient_links (bid_id)
    WHERE deleted_at IS NULL;

-- 2. Liens de suivi publics révoqués : l'ancien lien affiche « le destinataire de ce
--    colis a changé » au lieu de « lien invalide », sans jamais révéler le colis.
CREATE TABLE revoked_tracking_tokens (
    id          UUID PRIMARY KEY,
    token       VARCHAR(36) NOT NULL,
    bid_id      UUID NOT NULL REFERENCES bids(id),
    reason      VARCHAR(30) NOT NULL,
    revoked_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    deleted_at  TIMESTAMP,
    CONSTRAINT chk_revoked_tracking_tokens_reason CHECK (reason IN ('RECIPIENT_CHANGED'))
);

CREATE UNIQUE INDEX uq_revoked_tracking_tokens_token ON revoked_tracking_tokens (token);
CREATE INDEX idx_revoked_tracking_tokens_bid ON revoked_tracking_tokens (bid_id);
