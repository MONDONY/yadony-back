-- V248: discriminant de rail sur payments + références des opérations pawaPay.
-- stripe_payment_intent_id devient nullable (un paiement mobile money n'a pas de
-- PaymentIntent) ; uq_payments_stripe_pi_id est conservé, PostgreSQL accepte plusieurs NULL.
ALTER TABLE payments ALTER COLUMN stripe_payment_intent_id DROP NOT NULL;

ALTER TABLE payments
    ADD COLUMN rail               VARCHAR(10) NOT NULL DEFAULT 'STRIPE',
    ADD COLUMN pawapay_deposit_id UUID,
    ADD COLUMN pawapay_payout_id  UUID,
    ADD COLUMN pawapay_refund_id  UUID;

ALTER TABLE payments
    ADD CONSTRAINT payments_rail_check CHECK (rail IN ('STRIPE', 'PAWAPAY'));

CREATE INDEX idx_payments_rail_pawapay ON payments (rail) WHERE rail = 'PAWAPAY';
