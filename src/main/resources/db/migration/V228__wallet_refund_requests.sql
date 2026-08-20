CREATE TABLE wallet_refund_requests (
    id           UUID          NOT NULL DEFAULT gen_random_uuid(),
    user_id      UUID          NOT NULL,
    currency     VARCHAR(3)    NOT NULL,
    amount       NUMERIC(10,2) NOT NULL,
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMPTZ,
    resolved_by  UUID,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ,
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT pk_wallet_refund_requests PRIMARY KEY (id),
    CONSTRAINT fk_wallet_refund_requests_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_wallet_refund_requests_status CHECK (status IN ('PENDING', 'RESOLVED')),
    CONSTRAINT chk_wallet_refund_requests_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF')),
    CONSTRAINT chk_wallet_refund_requests_amount_positive CHECK (amount > 0)
);

-- Une seule demande PENDING par (user, devise) à la fois : un second appel à
-- /auth/me/wallet-refund-request avant résolution ne doit pas dupliquer le ticket.
CREATE UNIQUE INDEX uq_wallet_refund_requests_pending
    ON wallet_refund_requests (user_id, currency)
    WHERE status = 'PENDING';

CREATE INDEX idx_wallet_refund_requests_status ON wallet_refund_requests (status, requested_at);
