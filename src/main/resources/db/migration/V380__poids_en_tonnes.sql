-- V380 : poids en tonnes (route et bateau, lot 2 des modes de transport).
--
-- Les neuf colonnes de poids en NUMERIC(5,2) (999,99 kg au plus) passent en NUMERIC(8,2)
-- (999 999,99 kg). Précision augmentée à échelle égale : aucune valeur existante ne change et
-- PostgreSQL ne réécrit pas la table (changement binaire compatible). Aucune vue SQL ni fonction
-- ne référence ces colonnes.
--
-- Inchangées : trip_templates.available_kg (INTEGER, V108), trip_recurrences.available_kg
-- (DOUBLE PRECISION, V110, mappée sur un Double : ddl-auto validate), et
-- user_business_preferences.default_package_weight_kg (INTEGER, préférence dony-pro, hors lot).
--
-- Les plafonds par mode (32 kg pour une demande avion ou « peu importe », 999,99 kg pour un trajet
-- avion, 40 kg pour une série avion) sont appliqués en Java (WeightLimits), pas en base.

ALTER TABLE announcements
    ALTER COLUMN available_kg TYPE NUMERIC(8,2),
    ALTER COLUMN total_kg TYPE NUMERIC(8,2),
    ALTER COLUMN reserved_kg TYPE NUMERIC(8,2);

ALTER TABLE bids
    ALTER COLUMN weight_kg TYPE NUMERIC(8,2);

ALTER TABLE package_requests
    ALTER COLUMN weight_kg TYPE NUMERIC(8,2);

ALTER TABLE negotiation_threads
    ALTER COLUMN traveler_available_kg TYPE NUMERIC(8,2);

ALTER TABLE bid_amendments
    ALTER COLUMN added_weight_kg TYPE NUMERIC(8,2),
    ALTER COLUMN capacity_reserved_kg TYPE NUMERIC(8,2);

ALTER TABLE corridor_alerts
    ALTER COLUMN min_weight_kg TYPE NUMERIC(8,2);

-- Seul CHECK de poids à borne haute : 0,5..32 (V291) devient 0,5..999 999,99.
-- Plafond de 32 kg appliqué en Java pour l'avion et "peu importe" (WeightLimits).
-- Élargir la borne ne rend aucune ligne existante invalide.
ALTER TABLE package_requests
    DROP CONSTRAINT IF EXISTS chk_pkg_req_weight,
    ADD CONSTRAINT chk_pkg_req_weight CHECK (weight_kg BETWEEN 0.5 AND 999999.99);

-- CHECK de poids relus et gardés tels quels (sans borne haute, la précision de la colonne suffit) :
--   chk_announcements_available_kg (available_kg >= 0, V43)
--   chk_bids_weight_kg (weight_kg > 0, V3)
--   chk_neg_thread_kg (traveler_available_kg > 0, V58)
--   chk_bid_amendments_weight (added_weight_kg >= 0, V347)
--   chk_bid_amendments_capacity (0 <= capacity_reserved_kg <= added_weight_kg, V347)
-- Hors poids de colis, non touché : chk_pkg_weight (default_package_weight_kg 1..50, V101).
