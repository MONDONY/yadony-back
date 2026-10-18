-- Suivi du virement bancaire du voyageur payé par carte (rail Stripe, « separate charges and
-- transfers »). Le Transfer de la livraison crédite le compte Connect du voyageur
-- (destination_payment py_… sur ce compte) ; l'argent y devient disponible à available_on, puis
-- Stripe le vire automatiquement vers l'IBAN (payout po_…, calendrier par défaut). Une ligne par
-- paiement transféré : elle porte le Transfer, la date de disponibilité et le dernier payout
-- connu qui contient ce paiement (webhook des comptes connectés payout.*, ou rattrapage planifié).
--
-- Table à part plutôt que des colonnes sur payments : PaymentEntity n'a ni @DynamicUpdate ni
-- @Version, et toute colonne posée hors des UPDATE ciblés serait réécrite par un flush périmé.
-- Soft delete (deleted_at) comme toute entité, jamais de DELETE physique.

CREATE TABLE bank_payout_tracking (
    id                      UUID         NOT NULL,
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL,
    deleted_at              TIMESTAMP    NULL,
    payment_id              UUID         NOT NULL,
    traveler_id             UUID         NULL,
    transfer_id             VARCHAR(255) NOT NULL,
    connected_account_id    VARCHAR(255) NOT NULL,
    destination_payment_id  VARCHAR(255) NULL,
    available_on            DATE         NULL,
    payout_id               VARCHAR(255) NULL,
    payout_status           VARCHAR(20)  NULL,
    payout_event_at         TIMESTAMPTZ  NULL,
    arrival_date            DATE         NULL,
    paid_at                 TIMESTAMPTZ  NULL,
    failure_code            VARCHAR(100) NULL,
    notified_key            VARCHAR(300) NULL,
    CONSTRAINT pk_bank_payout_tracking PRIMARY KEY (id),
    CONSTRAINT uq_bank_payout_tracking_payment UNIQUE (payment_id),
    CONSTRAINT fk_bank_payout_tracking_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT chk_bank_payout_tracking_payout_status
        CHECK (payout_status IS NULL
               OR payout_status IN ('pending', 'in_transit', 'paid', 'failed', 'canceled'))
);

-- Rapprochement d'un payout : ses balance transactions donnent les py_… qu'il contient.
CREATE INDEX idx_bank_payout_tracking_destination_payment
    ON bank_payout_tracking (destination_payment_id);
-- Événements d'un même payout (created → paid / failed / canceled) et payout_failure.
CREATE INDEX idx_bank_payout_tracking_payout
    ON bank_payout_tracking (payout_id);
-- Rattrapage par compte connecté.
CREATE INDEX idx_bank_payout_tracking_account
    ON bank_payout_tracking (connected_account_id);
