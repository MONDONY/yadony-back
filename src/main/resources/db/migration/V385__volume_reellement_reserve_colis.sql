-- V385 : volume réellement pris par un colis sur un trajet vendu au m³ (lot 3, surréservation).
--
-- Un fil déjà payé dont le trajet s'est rempli pendant le paiement est matérialisé quand même : le trajet est
-- ramené à 0 m³ et le colis ne prend que ce qui restait. Sans mémoire de ce volume, l'annulation du colis rendait
-- tout son volume au trajet : du volume fantôme, revendu ensuite (surréservation qui se reproduit).
--
-- capacity_reserved_volume_m3 : volume réellement retiré du trajet par le colis, posé à la réservation, remis à 0
-- quand il est rendu. Nul pour les colis réservés avant V385 et ceux qui ne prennent rien (trajet dédié, hors m³) :
-- le rendu retombe alors sur volume_m3 (règle d'avant), plafonné au volume proposé.

ALTER TABLE bids
    ADD COLUMN capacity_reserved_volume_m3 NUMERIC(8,3);

ALTER TABLE bids
    ADD CONSTRAINT chk_bids_capacity_reserved_volume
        CHECK (capacity_reserved_volume_m3 IS NULL
               OR (capacity_reserved_volume_m3 >= 0
                   AND (volume_m3 IS NULL OR capacity_reserved_volume_m3 <= volume_m3)));
