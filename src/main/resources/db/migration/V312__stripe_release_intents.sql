-- V312 : versements carte « réserver → appeler → finaliser » (PROD_CHECKLIST 9.23).
--
-- Le claim ESCROW → RELEASED est commité AVANT l'appel Stripe (Transfer ou capture legacy),
-- pour qu'aucune connexion JDBC ni aucun verrou de ligne ne soit tenu pendant l'appel réseau.
-- Cette table garde, dans la même transaction que le claim, tout ce qu'il faut pour rejouer
-- l'appel à l'identique (même montant, même destination, même clé d'idempotence
-- transfer-<paymentId> / capture-<paymentId>) si le processus meurt ou si Stripe ne répond pas
-- entre l'appel et la finalisation. Le job StripeReleaseRecoveryJob reprend les lignes PENDING
-- dont le bail (lease_until) est échu.
--
-- Une ligne par paiement (uq_stripe_release_intents_payment) : un versement annulé (REVERTED,
-- CANCELLED) puis retenté réutilise la même ligne, révision suivante. Jamais supprimée.
CREATE TABLE stripe_release_intents (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id         UUID         NOT NULL REFERENCES payments (id),
    -- TRANSFER (séparation charge / transfer) | LEGACY_CAPTURE (destination charge historique)
    kind               VARCHAR(20)  NOT NULL,
    -- PENDING (appel Stripe à faire ou à confirmer) | DONE | REVERTED (Stripe a refusé ou garde
    -- apparue, claim annulé) | CANCELLED (annulée par un super-admin après recherche Stripe vide)
    status             VARCHAR(16)  NOT NULL,
    -- Révision de la réservation, incrémentée à chaque nouvelle réservation du même paiement :
    -- la clé d'idempotence est transfer-<paymentId>-r<revision>, jamais réutilisée avec d'autres
    -- paramètres (la recherche par transfer_group payment_<id> protège des doublons).
    revision           INT          NOT NULL DEFAULT 1,
    amount_minor       BIGINT,
    currency           VARCHAR(3),
    destination        VARCHAR(255),
    source_transaction VARCHAR(255),
    source             VARCHAR(64)  NOT NULL,
    audit_action       VARCHAR(64)  NOT NULL,
    bid_id             UUID,
    traveler_id        UUID,
    sender_id          UUID,
    -- Gardes levées par une dérogation admin motivée et auditée (libération forcée), une par une :
    -- DISPUTED (litige bancaire), BENEFICIARY_HELD (voyageur gelé). Jamais le litige admin, le
    -- colis annulé ou remboursé, ni le statut du paiement. NULL = aucune.
    overridden_guards  VARCHAR(64),
    -- Jeton du détenteur du bail (fencing) : renouvelé à chaque prise de bail ; une écriture d'un
    -- détenteur dont le bail a été repris est refusée, et il n'envoie plus d'appel Stripe.
    lease_token        UUID,
    -- Dernier envoi de Transfer.create pour la révision courante (NULL : jamais envoyé). Aucune
    -- annulation de la réservation tant qu'un envoi est récent (marge de sûreté), même si la
    -- recherche Stripe est vide : la réponse de Stripe peut être encore en route.
    create_sent_at     TIMESTAMPTZ,
    attempts           INT          NOT NULL DEFAULT 0,
    lease_until        TIMESTAMPTZ  NOT NULL,
    last_error         TEXT,
    stripe_object_id   VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT uq_stripe_release_intents_payment UNIQUE (payment_id),
    CONSTRAINT ck_stripe_release_intents_kind CHECK (kind IN ('TRANSFER', 'LEGACY_CAPTURE')),
    CONSTRAINT ck_stripe_release_intents_status CHECK (status IN ('PENDING', 'DONE', 'REVERTED', 'CANCELLED')),
    CONSTRAINT ck_stripe_release_intents_transfer CHECK (
        kind <> 'TRANSFER' OR (amount_minor IS NOT NULL AND amount_minor > 0
                              AND currency IS NOT NULL AND destination IS NOT NULL))
);

CREATE INDEX idx_stripe_release_intents_due ON stripe_release_intents (lease_until) WHERE status = 'PENDING';

COMMENT ON TABLE stripe_release_intents IS
    'Versement carte réservé (claim RELEASED commité) dont l''appel Stripe reste à faire ou à confirmer. Rejoué à l''identique par StripeReleaseRecoveryJob. Jamais supprimé.';
