-- V347 : modification d'un colis accepté (l'expéditeur ajoute des kilos ou des articles de la grille).
-- Une modification est une 2e transaction à part : elle ne touche JAMAIS bids.weight_kg, bid_grid_items
-- ni la ligne payments du principal (uq_payments_bid_id, V5), et ne crée aucune réservation
-- stripe_release_intents (UNIQUE payment_id, V312). Elle porte ses propres références prestataires.
-- Calquée sur V338 (bid_supplements) ; le supplément reste une table distincte.

CREATE TABLE bid_amendments (
    id                            UUID PRIMARY KEY,
    version                       BIGINT        NOT NULL DEFAULT 0,
    bid_id                        UUID          NOT NULL REFERENCES bids(id),
    announcement_id               UUID          NOT NULL REFERENCES announcements(id),
    traveler_id                   UUID          NOT NULL REFERENCES users(id),
    sender_id                     UUID          NOT NULL REFERENCES users(id),
    rail                          VARCHAR(10)   NOT NULL,
    status                        VARCHAR(25)   NOT NULL,
    currency                      VARCHAR(3)    NOT NULL,
    -- Kilos ajoutés, et kilos réellement retirés de l'annonce (remis à 0 quand ils sont rendus).
    added_weight_kg               NUMERIC(5,2)  NOT NULL DEFAULT 0,
    capacity_reserved_kg          NUMERIC(5,2)  NOT NULL DEFAULT 0,
    net_amount                    NUMERIC(14,2) NOT NULL,
    commission_amount             NUMERIC(14,2) NOT NULL,
    gross_amount                  NUMERIC(14,2) NOT NULL,
    commission_grid_id            UUID          NULL,
    commission_rate_snapshot      NUMERIC(7,6)  NULL,
    expires_at                    TIMESTAMP     NOT NULL,
    payment_expires_at            TIMESTAMP     NULL,
    responded_at                  TIMESTAMP     NULL,
    accepted_at                   TIMESTAMP     NULL,
    paid_out_at                   TIMESTAMP     NULL,
    refunded_at                   TIMESTAMP     NULL,
    reminder_sent_at              TIMESTAMP     NULL,
    -- Présentée au voyageur (passage en PROPOSED) : à la création (espèces, mobile money) ou à l'autorisation
    -- carte. Nulle : jamais présentée, le voyageur ne la voit pas.
    proposed_at                   TIMESTAMP     NULL,
    closed_reason                 VARCHAR(40)   NULL,
    -- Rail carte : PaymentIntent en capture manuelle (autorisation à la création, capture à l'acceptation).
    stripe_payment_intent_id      VARCHAR(255)  NULL,
    stripe_charge_id              VARCHAR(255)  NULL,
    stripe_transfer_id            VARCHAR(255)  NULL,
    stripe_refund_id              VARCHAR(255)  NULL,
    -- Remboursement demandé pendant un versement carte en cours (PAYOUT_PENDING) : si le Transfer n'est jamais parti,
    -- la modification est remboursée au lieu de revenir à ACCEPTED.
    refund_requested_at           TIMESTAMP     NULL,
    -- Autorisation d'une modification close rendue à l'expéditeur (PaymentIntent annulée, ou déjà annulée) : le
    -- filet ne relit plus Stripe pour elle.
    authorization_released_at     TIMESTAMP     NULL,
    -- Dernier essai du filet pour rendre cette autorisation, en échec : les essais ratés passent en fin de lot.
    release_attempted_at          TIMESTAMP     NULL,
    -- Essai de capture : incrémenté après une réponse 5xx de Stripe, pour changer de clé d'idempotence.
    capture_attempt               INTEGER       NOT NULL DEFAULT 0,
    -- Rail espèces : commission de la modification prélevée au voyageur (solde ou carte commission).
    commission_charged_via        VARCHAR(10)   NULL,
    commission_payment_intent_id  VARCHAR(255)  NULL,
    commission_status             VARCHAR(20)   NULL,
    created_at                    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMP     NOT NULL DEFAULT now(),
    deleted_at                    TIMESTAMP     NULL,

    CONSTRAINT chk_bid_amendments_rail
        CHECK (rail IN ('STRIPE', 'PAWAPAY', 'CASH')),
    CONSTRAINT chk_bid_amendments_status
        CHECK (status IN ('AUTHORIZATION_PENDING', 'PROPOSED', 'COMMISSION_PENDING', 'PAYMENT_PENDING',
                          'ACCEPTED', 'PAYOUT_PENDING', 'PAID_OUT', 'DECLINED', 'EXPIRED', 'CANCELLED',
                          'REFUND_PENDING', 'REFUNDED')),
    CONSTRAINT chk_bid_amendments_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF')),
    CONSTRAINT chk_bid_amendments_amounts
        CHECK (net_amount > 0 AND commission_amount >= 0
               AND gross_amount = net_amount + commission_amount),
    CONSTRAINT chk_bid_amendments_weight
        CHECK (added_weight_kg >= 0),
    -- Les kilos retirés de l'annonce ne dépassent jamais les kilos demandés (0 une fois rendus).
    CONSTRAINT chk_bid_amendments_capacity
        CHECK (capacity_reserved_kg >= 0 AND capacity_reserved_kg <= added_weight_kg),
    CONSTRAINT chk_bid_amendments_commission_via
        CHECK (commission_charged_via IS NULL OR commission_charged_via IN ('WALLET', 'CARD')),
    CONSTRAINT chk_bid_amendments_commission_status
        CHECK (commission_status IS NULL
               OR commission_status IN ('REQUIRES_3DS', 'CHARGED', 'FAILED', 'REFUNDED', 'REFUND_FAILED')),
    -- Champs par rail : seule une modification espèces a une commission séparée, seule une modification
    -- carte a des références Stripe.
    CONSTRAINT chk_bid_amendments_cash_fields
        CHECK (rail = 'CASH' OR (commission_charged_via IS NULL AND commission_payment_intent_id IS NULL
                                 AND commission_status IS NULL)),
    CONSTRAINT chk_bid_amendments_card_fields
        CHECK (rail = 'STRIPE' OR (stripe_payment_intent_id IS NULL AND stripe_charge_id IS NULL
                                   AND stripe_transfer_id IS NULL AND stripe_refund_id IS NULL
                                   AND authorization_released_at IS NULL AND release_attempted_at IS NULL
                                   AND capture_attempt = 0)),
    -- Demande de remboursement pendant un versement : seulement là où l'app verse (carte, mobile money).
    CONSTRAINT chk_bid_amendments_refund_requested_online
        CHECK (rail <> 'CASH' OR refund_requested_at IS NULL),
    -- Espèces en main propre à la remise : l'app n'encaisse rien, ne verse rien, ne rembourse rien.
    CONSTRAINT chk_bid_amendments_no_payout_cash
        CHECK (rail <> 'CASH' OR status NOT IN ('PAYMENT_PENDING', 'PAYOUT_PENDING', 'PAID_OUT', 'REFUND_PENDING'))
);

-- Une seule modification « ouverte » (en attente d'une autorisation, d'une réponse ou d'un paiement)
-- par colis. Les modifications acceptées s'empilent (plafond applicatif max-accepted-per-bid).
CREATE UNIQUE INDEX uq_bid_amendments_one_open ON bid_amendments (bid_id)
    WHERE deleted_at IS NULL
      AND status IN ('AUTHORIZATION_PENDING', 'PROPOSED', 'COMMISSION_PENDING', 'PAYMENT_PENDING');

CREATE UNIQUE INDEX uq_bid_amendments_stripe_pi ON bid_amendments (stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bid_amendments_stripe_charge ON bid_amendments (stripe_charge_id)
    WHERE stripe_charge_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bid_amendments_stripe_transfer ON bid_amendments (stripe_transfer_id)
    WHERE stripe_transfer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bid_amendments_commission_pi ON bid_amendments (commission_payment_intent_id)
    WHERE commission_payment_intent_id IS NOT NULL;

CREATE INDEX idx_bid_amendments_bid ON bid_amendments (bid_id);
CREATE INDEX idx_bid_amendments_open ON bid_amendments (expires_at)
    WHERE status IN ('AUTHORIZATION_PENDING', 'PROPOSED', 'COMMISSION_PENDING', 'PAYMENT_PENDING');

-- Articles de grille ajoutés : instantané du libellé et du prix net au moment de la demande
-- (même forme que bid_grid_items, V93).
CREATE TABLE bid_amendment_items (
    id                          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    amendment_id                UUID          NOT NULL REFERENCES bid_amendments(id),
    announcement_grid_item_id   UUID          NOT NULL REFERENCES announcement_price_grid_items(id),
    label_snapshot              VARCHAR(100)  NOT NULL,
    unit_price_net_snapshot     NUMERIC(10,2) NOT NULL,
    quantity                    INT           NOT NULL CHECK (quantity > 0),
    created_at                  TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX idx_bid_amendment_items_amendment ON bid_amendment_items (amendment_id);

-- pawaPay : 5e usage d'une opération, jamais rattaché à payments (payment_id reste NULL).
ALTER TABLE pawapay_operations ADD COLUMN bid_amendment_id UUID NULL REFERENCES bid_amendments(id);
ALTER TABLE pawapay_operations DROP CONSTRAINT chk_pawapay_ops_purpose;
ALTER TABLE pawapay_operations ADD CONSTRAINT chk_pawapay_ops_purpose
    CHECK (purpose IN ('BID_PAYMENT', 'WALLET_TOPUP', 'WALLET_REFUND', 'BID_SUPPLEMENT', 'BID_AMENDMENT'));
ALTER TABLE pawapay_operations ADD CONSTRAINT chk_pawapay_ops_purpose_amendment
    CHECK ((purpose = 'BID_AMENDMENT') = (bid_amendment_id IS NOT NULL));
-- Calqué sur uq_pawapay_ops_live_per_supplement (V338) : une opération vivante ou aboutie par
-- (modification, type).
CREATE UNIQUE INDEX uq_pawapay_ops_live_per_amendment ON pawapay_operations (bid_amendment_id, kind)
    WHERE status NOT IN ('FAILED', 'SUBMIT_REJECTED') AND bid_amendment_id IS NOT NULL;

-- Grand livre : débit de la commission de la modification (espèces). Recrédit = REFUND existant.
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD',
             'ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION',
             'ADMIN_CREDIT','ADMIN_DEBIT','TOP_UP_CODE','SUPPLEMENT_COMMISSION_DEDUCTED',
             'AMENDMENT_COMMISSION_DEDUCTED')
);
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS chk_wallet_tx_sign_matches_type;
ALTER TABLE wallet_transactions ADD CONSTRAINT chk_wallet_tx_sign_matches_type CHECK (
    NOT (type IN ('TOP_UP', 'REFUND', 'REFERRAL_REWARD', 'ADMIN_CREDIT', 'TOP_UP_CODE') AND amount < 0)
    AND NOT (type IN ('BID_PAYMENT', 'COMMISSION_DEDUCTED', 'ADMIN_REFUND_OUT', 'SELF_REFUND_OUT',
                      'FORFEITED_ON_DELETION', 'ADMIN_DEBIT', 'SUPPLEMENT_COMMISSION_DEDUCTED',
                      'AMENDMENT_COMMISSION_DEDUCTED') AND amount > 0)
);
