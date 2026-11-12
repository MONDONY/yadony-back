-- V381 : taille d'un colis (S, M, L, XL) et poids maximal par colis (lot 2 des modes de transport,
-- route). Tout est facultatif : les anciennes apps (prod) n'envoient ni taille ni poids max, et les
-- lignes existantes restent NULL (aucun contrôle côté back, affichage inchangé).
--
-- Trajet (annonce), modèle et série : taille maximale acceptée par colis + poids maximal par colis.
-- Demande de colis et réservation : taille du colis.
--
-- Types : announcements.max_parcel_weight_kg en NUMERIC(8,2) (comme les poids de V380, mappé sur un
-- BigDecimal) ; trip_templates / trip_recurrences en DOUBLE PRECISION (mappés sur un Double, comme
-- leurs autres colonnes décimales depuis V109, et ddl-auto: validate).
--
-- Rien sur chk_announcements_arrival_date, chk_trip_templates_arrival_day_offset et
-- chk_trip_recurrences_arrival_day_offset : déjà élargis à 120 jours par V379.

ALTER TABLE announcements
    ADD COLUMN max_item_size VARCHAR(2),
    ADD COLUMN max_parcel_weight_kg NUMERIC(8,2);

ALTER TABLE package_requests
    ADD COLUMN item_size VARCHAR(2);

ALTER TABLE bids
    ADD COLUMN item_size VARCHAR(2);

ALTER TABLE trip_templates
    ADD COLUMN max_item_size VARCHAR(2),
    ADD COLUMN max_parcel_weight_kg DOUBLE PRECISION;

ALTER TABLE trip_recurrences
    ADD COLUMN max_item_size VARCHAR(2),
    ADD COLUMN max_parcel_weight_kg DOUBLE PRECISION;

-- Tailles : S, M, L, XL ou NULL (un CHECK laisse passer NULL).
ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_max_item_size CHECK (max_item_size IN ('S', 'M', 'L', 'XL'));
ALTER TABLE package_requests
    ADD CONSTRAINT chk_package_requests_item_size CHECK (item_size IN ('S', 'M', 'L', 'XL'));
ALTER TABLE bids
    ADD CONSTRAINT chk_bids_item_size CHECK (item_size IN ('S', 'M', 'L', 'XL'));
ALTER TABLE trip_templates
    ADD CONSTRAINT chk_trip_templates_max_item_size CHECK (max_item_size IN ('S', 'M', 'L', 'XL'));
ALTER TABLE trip_recurrences
    ADD CONSTRAINT chk_trip_recurrences_max_item_size CHECK (max_item_size IN ('S', 'M', 'L', 'XL'));

-- Poids maximal par colis : 0,5 kg au moins (minimum d'une demande, V291/V380) et 999 999,99 kg au
-- plus (plafond de NUMERIC(8,2)). Les colonnes sont neuves : aucune ligne existante à revalider.
ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_max_parcel_weight
        CHECK (max_parcel_weight_kg IS NULL OR (max_parcel_weight_kg >= 0.5 AND max_parcel_weight_kg <= 999999.99));
ALTER TABLE trip_templates
    ADD CONSTRAINT chk_trip_templates_max_parcel_weight
        CHECK (max_parcel_weight_kg IS NULL OR (max_parcel_weight_kg >= 0.5 AND max_parcel_weight_kg <= 999999.99));
ALTER TABLE trip_recurrences
    ADD CONSTRAINT chk_trip_recurrences_max_parcel_weight
        CHECK (max_parcel_weight_kg IS NULL OR (max_parcel_weight_kg >= 0.5 AND max_parcel_weight_kg <= 999999.99));
