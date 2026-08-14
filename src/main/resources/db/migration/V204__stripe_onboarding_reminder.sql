-- Relance des onboardings Stripe Connect abandonnés.
--
-- Un voyageur qui ouvre l'onboarding puis l'abandonne reste PENDING_ONBOARDING
-- indéfiniment : rien ne le relançait, et il ne peut pas accepter la carte tant
-- que ce n'est pas fini. Cette colonne porte l'horodatage de la dernière
-- relance envoyée, ce qui rend le scheduler idempotent (il peut tourner toutes
-- les heures sans jamais renvoyer deux fois la même relance).
--
-- Nullable et sans valeur par défaut : NULL = jamais relancé. La cadence est
-- déduite de cette colonne et de users.stripe_account_created_at, sans compteur
-- séparé qui pourrait diverger.
ALTER TABLE users
    ADD COLUMN stripe_onboarding_last_reminder_at TIMESTAMPTZ;

-- Le scheduler ne balaie que les comptes réellement en attente. L'index partiel
-- garde ce balayage constant quand la table users grossit.
CREATE INDEX idx_users_stripe_onboarding_pending
    ON users (stripe_account_created_at)
    WHERE stripe_account_status = 'PENDING_ONBOARDING'
      AND deleted_at IS NULL;
