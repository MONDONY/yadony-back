-- V260 : une opération pawaPay peut servir une recharge ou un remboursement de wallet,
-- sans paiement de colis. purpose dit à quoi elle sert, user_id à qui.
-- Les colonnes fee_amount / pawapay_refund_id / pawapay_payout_id des items de
-- remboursement sont créées ici aussi (lot 2) pour n'avoir qu'une migration.

ALTER TABLE pawapay_operations
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'BID_PAYMENT',
    ADD COLUMN user_id UUID NULL REFERENCES users (id);

ALTER TABLE pawapay_operations
    ADD CONSTRAINT chk_pawapay_ops_purpose
        CHECK (purpose IN ('BID_PAYMENT', 'WALLET_TOPUP', 'WALLET_REFUND')),
    ADD CONSTRAINT chk_pawapay_ops_purpose_payment
        CHECK ((purpose = 'BID_PAYMENT') = (payment_id IS NOT NULL)),
    ADD CONSTRAINT chk_pawapay_ops_wallet_user
        CHECK (purpose = 'BID_PAYMENT' OR user_id IS NOT NULL);

CREATE INDEX idx_pawapay_ops_user_kind_status ON pawapay_operations (user_id, kind, status)
    WHERE user_id IS NOT NULL;

ALTER TABLE wallet_refund_request_items
    ADD COLUMN fee_amount NUMERIC(10,2) NOT NULL DEFAULT 0,
    ADD COLUMN pawapay_refund_id UUID NULL,
    ADD COLUMN pawapay_payout_id UUID NULL;

ALTER TABLE wallet_refund_requests DROP CONSTRAINT IF EXISTS chk_wallet_refund_requests_channel;
ALTER TABLE wallet_refund_requests ADD CONSTRAINT chk_wallet_refund_requests_channel
    CHECK (channel IN ('AUTOMATIC_STRIPE', 'AUTOMATIC_PAWAPAY', 'MANUAL_ADMIN'));
