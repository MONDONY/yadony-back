-- V241: opérations pawaPay (deposits, payouts, refunds). L'id est l'identifiant envoyé à
-- pawaPay, généré par yadony et persisté avant l'appel HTTP. Jamais supprimée.
CREATE TABLE pawapay_operations (
    id                      UUID          PRIMARY KEY,
    version                 BIGINT        NOT NULL DEFAULT 0,
    kind                    VARCHAR(10)   NOT NULL CHECK (kind IN ('DEPOSIT', 'PAYOUT', 'REFUND')),
    status                  VARCHAR(20)   NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'ACCEPTED', 'PROCESSING', 'ENQUEUED', 'IN_RECONCILIATION',
                          'COMPLETED', 'FAILED', 'SUBMIT_REJECTED')),
    amount                  NUMERIC(14,2) NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    provider                VARCHAR(30)   NOT NULL,
    country                 VARCHAR(2)    NOT NULL,
    msisdn                  VARCHAR(255)  NOT NULL,   -- chiffré (EncryptedStringConverter)
    msisdn_masked           VARCHAR(20)   NOT NULL,
    payment_id              UUID          REFERENCES payments(id),
    related_operation_id    UUID,
    authorization_url       TEXT,
    provider_transaction_id VARCHAR(100),
    failure_code            VARCHAR(64),
    failure_message         TEXT,
    raw_callback            TEXT,
    submitted_at            TIMESTAMPTZ,
    callback_received_at    TIMESTAMPTZ,
    last_polled_at          TIMESTAMPTZ,
    finalized_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Poller : seules les opérations non finales sont relues.
CREATE INDEX idx_pawapay_ops_open ON pawapay_operations (updated_at)
    WHERE status IN ('CREATED', 'ACCEPTED', 'PROCESSING', 'ENQUEUED', 'IN_RECONCILIATION');

CREATE INDEX idx_pawapay_ops_payment ON pawapay_operations (payment_id);

-- Verrou base contre le double deposit / payout / refund : au plus une opération vivante
-- ou aboutie par paiement et par type. Les opérations mortes (FAILED, SUBMIT_REJECTED)
-- n'y comptent pas : une relance reste possible.
CREATE UNIQUE INDEX uq_pawapay_ops_live_per_payment ON pawapay_operations (payment_id, kind)
    WHERE status NOT IN ('FAILED', 'SUBMIT_REJECTED') AND payment_id IS NOT NULL;
