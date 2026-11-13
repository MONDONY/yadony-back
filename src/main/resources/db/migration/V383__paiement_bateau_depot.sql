-- V383 : règle de versement du colis figée à son acceptation (lot 3, paiement bateau au dépôt).
--
-- payout_trigger dit QUAND l'argent part vers le transporteur :
--   DELIVERY : à la livraison confirmée, la règle de toujours, tous modes ;
--   DEPOSIT  : au dépôt du colis chez le transporteur (scan de départ).
-- Elle est décidée une seule fois, au passage du colis à ACCEPTED (PayoutTriggerPolicy.freeze) :
-- DEPOSIT si et seulement si, à cet instant, le trajet est un bateau, l'interrupteur bateau est
-- allumé et le transporteur est vérifié (GP Pro ou compte PRO). Elle n'est jamais recalculée :
-- changer le mode du trajet, éteindre l'interrupteur ou retirer le badge ensuite ne la touche pas.
--
-- payout_trigger_frozen_at retient que la règle a été figée : nul tant que le colis n'a jamais été
-- accepté. Un colis remis en attente puis accepté de nouveau garde la règle de sa première
-- acceptation (freeze n'écrit pas sur une règle déjà figée).

ALTER TABLE bids
    ADD COLUMN payout_trigger VARCHAR(16) NOT NULL DEFAULT 'DELIVERY',
    ADD COLUMN payout_trigger_frozen_at TIMESTAMP;

ALTER TABLE bids
    ADD CONSTRAINT chk_bids_payout_trigger
        CHECK (payout_trigger IN ('DELIVERY', 'DEPOSIT'));

-- Un versement au dépôt n'existe que décidé à une acceptation : jamais de DEPOSIT non figé.
ALTER TABLE bids
    ADD CONSTRAINT chk_bids_payout_trigger_frozen
        CHECK (payout_trigger = 'DELIVERY' OR payout_trigger_frozen_at IS NOT NULL);

-- Les colis existants restent DELIVERY. Ceux qui ont déjà été acceptés (numéro de suivi posé à
-- l'acceptation, ou statut d'après l'acceptation pour les plus anciens) sont figés DELIVERY par
-- cette migration : une acceptation ultérieure ne pourra jamais les faire passer au dépôt.
-- L'horodatage est celui de la migration, pas celui de leur acceptation (inconnu ici).
-- Ceux qui n'ont jamais été acceptés restent non figés : leur acceptation à venir décidera.
UPDATE bids
SET payout_trigger_frozen_at = (now() AT TIME ZONE 'UTC')
WHERE tracking_number IS NOT NULL
   OR status IN ('ACCEPTED', 'HANDED_OVER', 'IN_TRANSIT', 'ARRIVED', 'COMPLETED', 'NO_SHOW',
                 'PARCEL_REFUSED');
