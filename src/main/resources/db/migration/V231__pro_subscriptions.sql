-- V231__pro_subscriptions.sql
-- Abonnement PRO : transforme le statut auto-déclaratif gratuit en abonnement
-- payant. users.is_pro_account n'est pas supprimé — il reste le drapeau lu par
-- tout le code PRO existant (matching, export fiscal, automatisations, quotas)
-- et devient une projection de cette table, maintenue par ProAccessSynchronizer.
CREATE TABLE pro_subscriptions (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id                UUID         NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    source                 VARCHAR(16)  NOT NULL,
    stripe_customer_id     VARCHAR(255),
    stripe_subscription_id VARCHAR(255),
    billing_cycle          VARCHAR(8),
    current_period_end     TIMESTAMPTZ,
    cancel_at_period_end   BOOLEAN      NOT NULL DEFAULT FALSE,
    grace_expires_at       TIMESTAMPTZ,
    past_due_since         TIMESTAMPTZ,
    granted_by_admin_id    UUID,
    admin_grant_reason     VARCHAR(500),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ,
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT pk_pro_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_pro_subscriptions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_pro_subscriptions_status
        CHECK (status IN ('ACTIVE', 'PAST_DUE', 'LEGACY_GRACE', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_pro_subscriptions_source
        CHECK (source IN ('STRIPE', 'ADMIN_GRANT', 'LEGACY_FREE')),
    CONSTRAINT chk_pro_subscriptions_cycle
        CHECK (billing_cycle IS NULL OR billing_cycle IN ('MONTHLY', 'YEARLY'))
);

-- Un utilisateur n'a qu'un abonnement vivant à la fois. Index partiel car le
-- soft delete laisse les lignes en place.
CREATE UNIQUE INDEX uq_pro_subscriptions_user
    ON pro_subscriptions (user_id)
    WHERE deleted_at IS NULL;

-- Servent les trois tâches planifiées de ProSubscriptionScheduler.
CREATE INDEX idx_pro_subscriptions_status_grace
    ON pro_subscriptions (status, grace_expires_at);
CREATE INDEX idx_pro_subscriptions_status_past_due
    ON pro_subscriptions (status, past_due_since);
CREATE INDEX idx_pro_subscriptions_status_period_end
    ON pro_subscriptions (status, current_period_end);

-- Recherche par identifiant Stripe lors du traitement des webhooks (lot 2).
CREATE INDEX idx_pro_subscriptions_stripe_subscription
    ON pro_subscriptions (stripe_subscription_id);

COMMENT ON COLUMN pro_subscriptions.past_due_since IS
    'Entrée en PAST_DUE, remis à NULL à la sortie. updated_at ne conviendrait '
    'pas : toute écriture sur la ligne le repousserait et le dunning ne '
    'finirait jamais.';

-- Backfill : les comptes déjà PRO au moment du déploiement reçoivent 60 jours
-- de grâce. ATTENTION — les tâches planifiées qui downgradent à l'échéance sont
-- désactivées par défaut (yadony.billing.scheduler-enabled=false) et ne doivent
-- être activées qu'une fois le lot 2 déployé : sans lui, aucun de ces comptes
-- n'a de moyen de payer.
INSERT INTO pro_subscriptions (user_id, status, source, grace_expires_at)
SELECT id, 'LEGACY_GRACE', 'LEGACY_FREE', NOW() + INTERVAL '60 days'
FROM users
WHERE is_pro_account = TRUE
  AND deleted_at IS NULL;
