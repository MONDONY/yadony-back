-- Recharge remboursée hors de l'app (tableau de bord Stripe) : l'argent est revenu sur la carte, mais
-- le portefeuille garde son crédit. Décision du propriétaire (29/10) : ni débit automatique (risque de
-- double débit avec un ticket manuel clôturé dans l'admin), ni simple alerte ; la devise est GELÉE dès
-- le webhook, jusqu'à ce qu'un admin régularise (correction de solde) puis lève le gel.
CREATE TABLE wallet_external_refund_holds (
    id                 UUID PRIMARY KEY,
    user_id            UUID           NOT NULL REFERENCES users (id),
    currency           VARCHAR(3)     NOT NULL,
    payment_intent_id  VARCHAR(255)   NOT NULL,
    stripe_refund_id   VARCHAR(255)   NOT NULL,
    amount             NUMERIC(10, 2) NOT NULL,
    status             VARCHAR(10)    NOT NULL,
    released_by        UUID,
    released_at        TIMESTAMP,
    release_note       VARCHAR(500),
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP,
    deleted_at         TIMESTAMP,
    CONSTRAINT uq_wallet_external_refund_holds_refund UNIQUE (stripe_refund_id),
    CONSTRAINT chk_wallet_external_refund_holds_status CHECK (status IN ('OPEN', 'RELEASED')),
    CONSTRAINT chk_wallet_external_refund_holds_amount CHECK (amount > 0),
    -- Un gel levé l'est toujours par un admin identifié, avec sa raison.
    CONSTRAINT chk_wallet_external_refund_holds_release CHECK (
        status <> 'RELEASED' OR (released_by IS NOT NULL AND released_at IS NOT NULL AND release_note IS NOT NULL))
);

CREATE INDEX idx_wallet_external_refund_holds_open ON wallet_external_refund_holds (user_id, currency)
    WHERE status = 'OPEN';
