-- V301 — FLUTTER-E2.
-- Destinataire absent à l'arrivée : procédure encadrée (A) et partage chiffré par l'admin (B).
--
-- A. Procédure. Aucun nouveau statut de bid ni de paiement : les machines à états existantes
--    (BidStatus, PaymentStatus, CancellationStatus) ne changent pas. La procédure vit sur la
--    ligne cancellations de portée DELIVERY qui porte déjà le signalement RECIPIENT_NO_SHOW.
--      * bids.arrived_at : heure de l'arrivée déclarée par le voyageur (point de départ du délai
--        d'attente minimal avant de pouvoir signaler). Nulle pour les colis arrivés avant V301.
--      * conversations.traveler_last_message_at : dernier message envoyé par le voyageur dans la
--        conversation (relayé par la Cloud Function onNewMessage → /internal/messaging/notify).
--        Preuve de tentative de contact vérifiable côté serveur, avec les appels (table calls).
--      * cancellations.contact_proof / contact_confirmed_at : preuve retenue et confirmation
--        explicite du voyageur au moment du signalement.
--      * cancellations.hold_until : fin de la garde du colis par le voyageur (signalement + 7 j).
--      * cancellations.retry_appointment_at / _note : nouveau rendez-vous fixé par l'expéditeur.
--      * cancellations.unclaimed_at : colis passé « non réclamé » (le net est alors libéré au
--        voyageur par le package payments). Posée une seule fois (claim atomique).
ALTER TABLE bids ADD COLUMN arrived_at TIMESTAMP;

ALTER TABLE conversations ADD COLUMN traveler_last_message_at TIMESTAMP;

ALTER TABLE cancellations
    ADD COLUMN contact_proof          VARCHAR(10),
    ADD COLUMN contact_confirmed_at   TIMESTAMPTZ,
    ADD COLUMN hold_until             TIMESTAMPTZ,
    ADD COLUMN retry_appointment_at   TIMESTAMPTZ,
    ADD COLUMN retry_appointment_note TEXT,
    ADD COLUMN unclaimed_at           TIMESTAMPTZ;

ALTER TABLE cancellations ADD CONSTRAINT cancellations_contact_proof_check
    CHECK (contact_proof IS NULL OR contact_proof IN ('CALL', 'MESSAGE'));

-- Un colis ne passe « non réclamé » qu'au terme d'une garde.
ALTER TABLE cancellations ADD CONSTRAINT cancellations_unclaimed_requires_hold_check
    CHECK (unclaimed_at IS NULL OR hold_until IS NOT NULL);

-- Scheduler horaire des gardes échues (UnclaimedParcelScheduler).
CREATE INDEX idx_cancellations_hold_due
    ON cancellations (hold_until)
    WHERE hold_until IS NOT NULL AND unclaimed_at IS NULL;

-- B. Partage chiffré décidé par l'admin à la résolution d'un litige.
--    disputes.* : la décision (lisible par l'admin et les parties).
--    payment_splits : l'exécution, étape par étape, pour reprendre un partage interrompu entre
--    le remboursement et le transfert. Une seule répartition par paiement (index unique) : un
--    double clic ou une reprise ne peut jamais créer un second remboursement.
ALTER TABLE disputes
    ADD COLUMN sender_refund_amount   NUMERIC(10, 2),
    ADD COLUMN traveler_payout_amount NUMERIC(10, 2),
    ADD COLUMN split_currency         VARCHAR(3);

ALTER TABLE disputes ADD CONSTRAINT disputes_split_amounts_check
    CHECK ((sender_refund_amount IS NULL) = (traveler_payout_amount IS NULL)
           AND (sender_refund_amount IS NULL OR (sender_refund_amount >= 0 AND traveler_payout_amount >= 0)));

CREATE TABLE payment_splits (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id             UUID           NOT NULL REFERENCES payments (id),
    dispute_id             UUID           REFERENCES disputes (id),
    bid_id                 UUID,
    traveler_id            UUID,
    sender_refund_amount   NUMERIC(10, 2) NOT NULL,
    traveler_payout_amount NUMERIC(10, 2) NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    mode                   VARCHAR(20)    NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    stripe_refund_id       VARCHAR(255),
    stripe_capture_done    BOOLEAN        NOT NULL DEFAULT FALSE,
    stripe_transfer_id     VARCHAR(255),
    attempts               INTEGER        NOT NULL DEFAULT 0,
    last_error             TEXT,
    decided_by             UUID,
    completed_at           TIMESTAMP,
    created_at             TIMESTAMP      NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    updated_at             TIMESTAMP      NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    deleted_at             TIMESTAMP,
    CONSTRAINT chk_payment_splits_amounts
        CHECK (sender_refund_amount >= 0 AND traveler_payout_amount >= 0
               AND sender_refund_amount + traveler_payout_amount > 0),
    CONSTRAINT chk_payment_splits_mode CHECK (mode IN ('REFUND_TRANSFER', 'PARTIAL_CAPTURE')),
    CONSTRAINT chk_payment_splits_status CHECK (status IN ('CLAIMED', 'SENDER_REFUNDED', 'COMPLETED'))
);

CREATE UNIQUE INDEX uq_payment_splits_payment
    ON payment_splits (payment_id)
    WHERE deleted_at IS NULL;

-- File des partages à reprendre (alerte admin + POST /admin/disputes/{id}/split/retry).
CREATE INDEX idx_payment_splits_incomplete
    ON payment_splits (created_at)
    WHERE status <> 'COMPLETED' AND deleted_at IS NULL;
