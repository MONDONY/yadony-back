-- V233__pro_subscriptions_granted_at.sql
-- La spécification de l'octroi PRO admin exige d'exposer la date d'octroi.
-- created_at désigne la création de la ligne recyclée (pas l'octroi), et
-- updated_at est écrasé par toute écriture ultérieure sur la ligne : ni l'un
-- ni l'autre ne peut en tenir lieu. Nullable : seul grantByAdmin la renseigne,
-- les deux autres créateurs (openLegacyGrace, activateFromStripe) la purgent.
ALTER TABLE pro_subscriptions ADD COLUMN granted_at TIMESTAMPTZ;
