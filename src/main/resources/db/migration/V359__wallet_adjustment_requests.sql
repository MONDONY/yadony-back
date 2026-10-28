-- Ajustement manuel du solde au-delà de 100 € d'équivalent : il n'est appliqué qu'après
-- l'approbation d'un SECOND admin (décision du propriétaire, 28/10). La demande garde tout ce
-- qu'il faut pour écrire le mouvement à l'approbation, et la clé d'idempotence de l'auteur.
CREATE TABLE wallet_adjustment_requests (
    id               UUID PRIMARY KEY,
    user_id          UUID           NOT NULL REFERENCES users (id),
    currency         VARCHAR(3)     NOT NULL,
    direction        VARCHAR(10)    NOT NULL,
    amount           NUMERIC(10, 2) NOT NULL,
    eur_equivalent   NUMERIC(12, 2) NOT NULL,
    reason           VARCHAR(500)   NOT NULL,
    idempotency_key  VARCHAR(220)   NOT NULL,
    requested_by     UUID           NOT NULL,
    status           VARCHAR(10)    NOT NULL,
    decided_by       UUID,
    decided_at       TIMESTAMP,
    decision_note    VARCHAR(500),
    transaction_id   UUID,
    created_at       TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP,
    deleted_at       TIMESTAMP,
    CONSTRAINT uq_wallet_adjustment_requests_key UNIQUE (idempotency_key),
    CONSTRAINT chk_wallet_adjustment_requests_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_wallet_adjustment_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_wallet_adjustment_requests_amount CHECK (amount > 0),
    -- Jamais approuvée par son propre auteur ; il peut en revanche retirer (rejeter) sa demande.
    CONSTRAINT chk_wallet_adjustment_requests_second_admin
        CHECK (status <> 'APPROVED' OR (decided_by IS NOT NULL AND decided_by <> requested_by))
);

CREATE INDEX idx_wallet_adjustment_requests_pending ON wallet_adjustment_requests (created_at)
    WHERE status = 'PENDING';
