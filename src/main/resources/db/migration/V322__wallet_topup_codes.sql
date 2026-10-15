-- Codes de recharge du solde (spec docs-claude 2026-10-15-codes-recharge-solde-design.md).
-- Un code partagé crédite le portefeuille de sa devise, une fois par compte, jusqu'à
-- max_redemptions. Crédit non-cash (TOP_UP_CODE) : jamais remboursable, perdu à la suppression.

CREATE TABLE wallet_topup_codes (
    id              UUID PRIMARY KEY,
    code            VARCHAR(32)   NOT NULL,
    amount          DECIMAL(10,2) NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    max_redemptions INTEGER       NOT NULL,
    redeemed_count  INTEGER       NOT NULL DEFAULT 0,
    expires_at      TIMESTAMP     NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    note            VARCHAR(500),
    created_by      UUID          NOT NULL,
    revoked_by      UUID,
    revoked_at      TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    deleted_at      TIMESTAMP,
    CONSTRAINT chk_topup_code_format CHECK (code ~ '^[A-Z0-9-]{4,32}$'),
    CONSTRAINT chk_topup_code_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_topup_code_currency CHECK (currency IN ('EUR','USD','CAD','GBP','CHF','XOF','XAF')),
    CONSTRAINT chk_topup_code_max_redemptions CHECK (max_redemptions BETWEEN 1 AND 10000),
    CONSTRAINT chk_topup_code_redeemed_range CHECK (redeemed_count >= 0 AND redeemed_count <= max_redemptions),
    CONSTRAINT chk_topup_code_status CHECK (status IN ('ACTIVE','REVOKED'))
);

-- Unicité y compris sur les lignes soft-deleted : un code ne renaît jamais avec un autre montant.
CREATE UNIQUE INDEX uq_wallet_topup_codes_code ON wallet_topup_codes (code);

CREATE TABLE wallet_topup_code_redemptions (
    id                    UUID PRIMARY KEY,
    code_id               UUID          NOT NULL REFERENCES wallet_topup_codes(id),
    user_id               UUID          NOT NULL REFERENCES users(id),
    amount                DECIMAL(10,2) NOT NULL,
    currency              VARCHAR(3)    NOT NULL,
    wallet_transaction_id UUID          NOT NULL REFERENCES wallet_transactions(id),
    created_at            TIMESTAMP     NOT NULL,
    CONSTRAINT chk_topup_code_redemption_amount CHECK (amount > 0),
    CONSTRAINT uq_topup_code_redemption_user UNIQUE (code_id, user_id)
);

CREATE INDEX idx_topup_code_redemptions_code ON wallet_topup_code_redemptions (code_id, created_at DESC);

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD',
             'ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION',
             'ADMIN_CREDIT','ADMIN_DEBIT','TOP_UP_CODE')
);

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS chk_wallet_tx_sign_matches_type;
ALTER TABLE wallet_transactions ADD CONSTRAINT chk_wallet_tx_sign_matches_type CHECK (
    NOT (type IN ('TOP_UP', 'REFUND', 'REFERRAL_REWARD', 'ADMIN_CREDIT', 'TOP_UP_CODE') AND amount < 0)
    AND NOT (type IN ('BID_PAYMENT', 'COMMISSION_DEDUCTED', 'ADMIN_REFUND_OUT', 'SELF_REFUND_OUT',
                      'FORFEITED_ON_DELETION', 'ADMIN_DEBIT') AND amount > 0)
);
