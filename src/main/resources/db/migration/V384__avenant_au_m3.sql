-- V384 : modification d'un colis au m³ (lot 3, trajet bateau vendu au volume).
--
-- Sur un trajet bateau vendu au m³ (prix au m³, aucun kilo), l'expéditeur ajoute du volume à son colis
-- accepté comme il ajoute des kilos ailleurs : net = volume ajouté × prix au m³ du trajet, même commission
-- différentielle que l'ajout de kilos, payée par l'expéditeur. Le volume est retiré du trajet à la création de
-- la modification et rendu une seule fois à sa clôture, comme les kilos (capacity_reserved_kg).
--
-- added_volume_m3             : volume ajouté par la demande, nul pour un ajout de kilos ou d'articles.
-- capacity_reserved_volume_m3 : volume réellement retiré du trajet ; 0 tant que rien n'est pris, remis à 0 au rendu.
-- Même précision que bids.volume_m3 et announcements.available_volume_m3 (NUMERIC(8,3), V382).

ALTER TABLE bid_amendments
    ADD COLUMN added_volume_m3 NUMERIC(8,3),
    ADD COLUMN capacity_reserved_volume_m3 NUMERIC(8,3) NOT NULL DEFAULT 0;

ALTER TABLE bid_amendments
    ADD CONSTRAINT chk_bid_amendments_volume
        CHECK (added_volume_m3 IS NULL OR added_volume_m3 > 0);

-- Le volume retiré du trajet ne dépasse jamais le volume demandé (0 une fois rendu).
ALTER TABLE bid_amendments
    ADD CONSTRAINT chk_bid_amendments_volume_capacity
        CHECK (capacity_reserved_volume_m3 >= 0
               AND capacity_reserved_volume_m3 <= COALESCE(added_volume_m3, 0));
