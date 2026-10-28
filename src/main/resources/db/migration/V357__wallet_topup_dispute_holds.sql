-- Litige carte (chargeback) sur une RECHARGE du portefeuille : le montant contesté est débité dans
-- la limite du solde et la devise reste gelée jusqu'à la clôture du litige ; un litige gagné
-- recrédite ce qui avait été débité. Avant, seule une alerte était levée et l'argent restait
-- dépensable.

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD',
             'ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION',
             'ADMIN_CREDIT','ADMIN_DEBIT','TOP_UP_CODE','SUPPLEMENT_COMMISSION_DEDUCTED',
             'AMENDMENT_COMMISSION_DEDUCTED','TOPUP_DISPUTED','TOPUP_DISPUTE_REVERSED')
);
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS chk_wallet_tx_sign_matches_type;
ALTER TABLE wallet_transactions ADD CONSTRAINT chk_wallet_tx_sign_matches_type CHECK (
    NOT (type IN ('TOP_UP', 'REFUND', 'REFERRAL_REWARD', 'ADMIN_CREDIT', 'TOP_UP_CODE',
                  'TOPUP_DISPUTE_REVERSED') AND amount < 0)
    AND NOT (type IN ('BID_PAYMENT', 'COMMISSION_DEDUCTED', 'ADMIN_REFUND_OUT', 'SELF_REFUND_OUT',
                      'FORFEITED_ON_DELETION', 'ADMIN_DEBIT', 'SUPPLEMENT_COMMISSION_DEDUCTED',
                      'AMENDMENT_COMMISSION_DEDUCTED', 'TOPUP_DISPUTED') AND amount > 0)
);

CREATE TABLE wallet_dispute_holds (
    id                 UUID PRIMARY KEY,
    user_id            UUID           NOT NULL REFERENCES users (id),
    currency           VARCHAR(3)     NOT NULL,
    stripe_dispute_id  VARCHAR(255)   NOT NULL,
    payment_intent_id  VARCHAR(255)   NOT NULL,
    disputed_amount    NUMERIC(10, 2) NOT NULL,
    debited_amount     NUMERIC(10, 2) NOT NULL,
    status             VARCHAR(10)    NOT NULL,
    closed_at          TIMESTAMP,
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP,
    deleted_at         TIMESTAMP,
    CONSTRAINT uq_wallet_dispute_holds_dispute UNIQUE (stripe_dispute_id),
    CONSTRAINT chk_wallet_dispute_holds_status CHECK (status IN ('OPEN', 'WON', 'LOST')),
    CONSTRAINT chk_wallet_dispute_holds_amounts CHECK (disputed_amount > 0 AND debited_amount >= 0
                                                       AND debited_amount <= disputed_amount)
);

-- Gel de la devise : une recherche par (utilisateur, devise) à chaque débit.
CREATE INDEX idx_wallet_dispute_holds_open ON wallet_dispute_holds (user_id, currency) WHERE status = 'OPEN';
