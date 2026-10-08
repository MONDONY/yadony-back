-- FLUTTER-4D : voyage à plusieurs étapes (ex. Paris → Abidjan, puis Abidjan → Douala).
-- Un voyage = N annonces classiques créées ensemble et reliées par un identifiant de
-- groupe purement informatif : recherche, alertes, bids, QR et paiement restent par
-- annonce, chaque étape a ses propres kilos, prix et colis.
--
-- trip_group_id  : identique pour toutes les étapes d'un même voyage, NULL pour un
--                  trajet isolé (tous les trajets existants).
-- trip_leg_index : rang de l'étape dans le voyage, à partir de 1. NULL hors voyage.

ALTER TABLE announcements ADD COLUMN IF NOT EXISTS trip_group_id  UUID     NULL;
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS trip_leg_index INTEGER  NULL;

ALTER TABLE announcements ADD CONSTRAINT chk_announcements_trip_leg
    CHECK ((trip_group_id IS NULL AND trip_leg_index IS NULL)
        OR (trip_group_id IS NOT NULL AND trip_leg_index >= 1));

-- Lecture des étapes sœurs d'une annonce (fiche, « Mes trajets », admin).
CREATE INDEX IF NOT EXISTS idx_announcements_trip_group
    ON announcements (trip_group_id, trip_leg_index)
    WHERE trip_group_id IS NOT NULL;
