-- V338 : supplément de prix demandé par le voyageur à la remise (une demande active par colis).
-- Un supplément ne crée JAMAIS de ligne payments (uq_payments_bid_id, V5) ni de réservation
-- stripe_release_intents (UNIQUE payment_id, V312) : il porte ses propres références prestataires.

CREATE TABLE bid_supplements (
    id                            UUID PRIMARY KEY,
    version                       BIGINT       NOT NULL DEFAULT 0,
    bid_id                        UUID         NOT NULL REFERENCES bids(id),
    traveler_id                   UUID         NOT NULL REFERENCES users(id),
    sender_id                     UUID         NOT NULL REFERENCES users(id),
    rail                          VARCHAR(10)  NOT NULL,
    status                        VARCHAR(20)  NOT NULL,
    currency                      VARCHAR(3)   NOT NULL,
    net_amount                    NUMERIC(14,2) NOT NULL,
    commission_amount             NUMERIC(14,2) NOT NULL,
    gross_amount                  NUMERIC(14,2) NOT NULL,
    commission_grid_id            UUID         NULL,
    commission_rate_snapshot      NUMERIC(7,6) NULL,
    reason_code                   VARCHAR(20)  NOT NULL,
    reason_text                   VARCHAR(500) NULL,
    grid_item_label               VARCHAR(120) NULL,
    photo_key                     VARCHAR(512) NOT NULL,
    -- Photo supprimée de R2 par la purge après livraison (BidSupplementPhotoPurgeParticipant) :
    -- la clé reste pour l'historique, la source de références ne la revendique plus.
    photo_purged_at               TIMESTAMP    NULL,
    expires_at                    TIMESTAMP    NOT NULL,
    responded_at                  TIMESTAMP    NULL,
    paid_at                       TIMESTAMP    NULL,
    paid_out_at                   TIMESTAMP    NULL,
    refunded_at                   TIMESTAMP    NULL,
    closed_reason                 VARCHAR(40)  NULL,
    -- Rail carte : PaymentIntent en capture automatique sur le compte plateforme.
    stripe_payment_intent_id      VARCHAR(255) NULL,
    stripe_pi_attempt             INT          NOT NULL DEFAULT 0,
    stripe_charge_id              VARCHAR(255) NULL,
    stripe_transfer_id            VARCHAR(255) NULL,
    stripe_refund_id              VARCHAR(255) NULL,
    -- Rail espèces : commission du supplément prélevée au voyageur (solde ou carte commission).
    commission_charged_via        VARCHAR(10)  NULL,
    commission_payment_intent_id  VARCHAR(255) NULL,
    commission_status             VARCHAR(20)  NULL,
    created_at                    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMP    NOT NULL DEFAULT now(),
    deleted_at                    TIMESTAMP    NULL,

    CONSTRAINT chk_bid_supplements_rail
        CHECK (rail IN ('STRIPE', 'PAWAPAY', 'CASH')),
    CONSTRAINT chk_bid_supplements_status
        CHECK (status IN ('COMMISSION_PENDING', 'PROPOSED', 'PAYMENT_PENDING', 'PAID',
                          'PAYOUT_PENDING', 'PAID_OUT', 'DECLINED', 'EXPIRED', 'WITHDRAWN',
                          'CANCELLED', 'REFUND_PENDING', 'REFUNDED')),
    CONSTRAINT chk_bid_supplements_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF')),
    CONSTRAINT chk_bid_supplements_amounts
        CHECK (net_amount > 0 AND commission_amount >= 0
               AND gross_amount = net_amount + commission_amount),
    CONSTRAINT chk_bid_supplements_reason
        CHECK (reason_code IN ('GRID_ITEM', 'EXTRA_WEIGHT', 'OTHER')),
    CONSTRAINT chk_bid_supplements_commission_via
        CHECK (commission_charged_via IS NULL OR commission_charged_via IN ('WALLET', 'CARD')),
    CONSTRAINT chk_bid_supplements_commission_status
        CHECK (commission_status IS NULL
               OR commission_status IN ('REQUIRES_3DS', 'CHARGED', 'FAILED', 'REFUNDED', 'REFUND_FAILED')),
    -- Champs par rail : un supplément espèces n'a ni PI Stripe ni versement ; seul l'espèces a une commission séparée.
    CONSTRAINT chk_bid_supplements_cash_fields
        CHECK (rail = 'CASH' OR (commission_charged_via IS NULL AND commission_payment_intent_id IS NULL
                                 AND commission_status IS NULL)),
    CONSTRAINT chk_bid_supplements_card_fields
        CHECK (rail = 'STRIPE' OR (stripe_payment_intent_id IS NULL AND stripe_charge_id IS NULL
                                   AND stripe_transfer_id IS NULL AND stripe_refund_id IS NULL)),
    CONSTRAINT chk_bid_supplements_no_payout_cash
        CHECK (rail <> 'CASH' OR status NOT IN ('PAYMENT_PENDING', 'PAYOUT_PENDING', 'PAID_OUT', 'REFUND_PENDING'))
);

-- Une seule demande « vivante » par colis (en attente, payée ou versée). Refusée, expirée,
-- retirée, annulée ou remboursée : une nouvelle demande devient possible (plafond applicatif).
CREATE UNIQUE INDEX uq_bid_supplements_one_active ON bid_supplements (bid_id)
    WHERE deleted_at IS NULL
      AND status IN ('COMMISSION_PENDING', 'PROPOSED', 'PAYMENT_PENDING', 'PAID',
                     'PAYOUT_PENDING', 'PAID_OUT', 'REFUND_PENDING');

CREATE UNIQUE INDEX uq_bid_supplements_stripe_pi ON bid_supplements (stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bid_supplements_stripe_charge ON bid_supplements (stripe_charge_id)
    WHERE stripe_charge_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bid_supplements_stripe_transfer ON bid_supplements (stripe_transfer_id)
    WHERE stripe_transfer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bid_supplements_commission_pi ON bid_supplements (commission_payment_intent_id)
    WHERE commission_payment_intent_id IS NOT NULL;

CREATE INDEX idx_bid_supplements_bid ON bid_supplements (bid_id);
CREATE INDEX idx_bid_supplements_open ON bid_supplements (expires_at)
    WHERE status IN ('COMMISSION_PENDING', 'PROPOSED', 'PAYMENT_PENDING');
CREATE INDEX idx_bid_supplements_unsettled ON bid_supplements (updated_at)
    WHERE status IN ('PAID', 'PAYOUT_PENDING', 'REFUND_PENDING');

-- pawaPay : 4e usage d'une opération, jamais rattaché à payments (payment_id reste NULL).
ALTER TABLE pawapay_operations ADD COLUMN bid_supplement_id UUID NULL REFERENCES bid_supplements(id);
ALTER TABLE pawapay_operations DROP CONSTRAINT chk_pawapay_ops_purpose;
ALTER TABLE pawapay_operations ADD CONSTRAINT chk_pawapay_ops_purpose
    CHECK (purpose IN ('BID_PAYMENT', 'WALLET_TOPUP', 'WALLET_REFUND', 'BID_SUPPLEMENT'));
ALTER TABLE pawapay_operations ADD CONSTRAINT chk_pawapay_ops_purpose_supplement
    CHECK ((purpose = 'BID_SUPPLEMENT') = (bid_supplement_id IS NOT NULL));
-- Calqué sur uq_pawapay_ops_live_per_payment (V246) : une opération vivante ou aboutie par (supplément, type).
CREATE UNIQUE INDEX uq_pawapay_ops_live_per_supplement ON pawapay_operations (bid_supplement_id, kind)
    WHERE status NOT IN ('FAILED', 'SUBMIT_REJECTED') AND bid_supplement_id IS NOT NULL;

-- Grand livre : débit de la commission du supplément (espèces). Recrédit = REFUND existant.
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD',
             'ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION',
             'ADMIN_CREDIT','ADMIN_DEBIT','TOP_UP_CODE','SUPPLEMENT_COMMISSION_DEDUCTED')
);
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS chk_wallet_tx_sign_matches_type;
ALTER TABLE wallet_transactions ADD CONSTRAINT chk_wallet_tx_sign_matches_type CHECK (
    NOT (type IN ('TOP_UP', 'REFUND', 'REFERRAL_REWARD', 'ADMIN_CREDIT', 'TOP_UP_CODE') AND amount < 0)
    AND NOT (type IN ('BID_PAYMENT', 'COMMISSION_DEDUCTED', 'ADMIN_REFUND_OUT', 'SELF_REFUND_OUT',
                      'FORFEITED_ON_DELETION', 'ADMIN_DEBIT', 'SUPPLEMENT_COMMISSION_DEDUCTED') AND amount > 0)
);
