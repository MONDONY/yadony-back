ALTER TABLE wallet_accounts
    ADD COLUMN refund_eligible_amount NUMERIC(10,2) NOT NULL DEFAULT 0,
    ADD COLUMN refund_eligible_since TIMESTAMPTZ;

ALTER TABLE wallet_refund_requests
    ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'MANUAL_ADMIN';

ALTER TABLE wallet_refund_requests
    ADD CONSTRAINT chk_wallet_refund_requests_channel
        CHECK (channel IN ('AUTOMATIC_STRIPE', 'MANUAL_ADMIN'));

ALTER TABLE wallet_refund_requests DROP CONSTRAINT chk_wallet_refund_requests_status;
ALTER TABLE wallet_refund_requests ADD CONSTRAINT chk_wallet_refund_requests_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'RESOLVED', 'REFUNDED', 'FAILED'));

DROP INDEX uq_wallet_refund_requests_pending;
CREATE UNIQUE INDEX uq_wallet_refund_requests_pending
    ON wallet_refund_requests (user_id, currency)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE wallet_refund_request_items (
    id                     UUID          NOT NULL DEFAULT gen_random_uuid(),
    refund_request_id      UUID          NOT NULL,
    wallet_transaction_id  UUID          NOT NULL,
    payment_intent_id      VARCHAR(255)  NOT NULL,
    stripe_refund_id       VARCHAR(255),
    amount                 NUMERIC(10,2) NOT NULL,
    status                 VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ,
    CONSTRAINT pk_wallet_refund_request_items PRIMARY KEY (id),
    CONSTRAINT fk_wallet_refund_request_items_request
        FOREIGN KEY (refund_request_id) REFERENCES wallet_refund_requests(id),
    CONSTRAINT fk_wallet_refund_request_items_tx
        FOREIGN KEY (wallet_transaction_id) REFERENCES wallet_transactions(id),
    CONSTRAINT chk_wallet_refund_request_items_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'REFUNDED', 'FAILED')),
    CONSTRAINT chk_wallet_refund_request_items_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_wallet_refund_request_items_request ON wallet_refund_request_items (refund_request_id);
CREATE UNIQUE INDEX uq_wallet_refund_request_items_pi ON wallet_refund_request_items (payment_intent_id);

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD',
             'ADMIN_REFUND_OUT','SELF_REFUND_OUT')
);
