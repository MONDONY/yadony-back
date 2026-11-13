-- V382 : bateau (lot 3 des modes de transport). Capacité en m³, fourchette d'arrivée, dimensions du
-- colis et retards de traversée. Tout est facultatif : les lignes existantes (avion, route) restent
-- NULL et rien ne change pour elles.
--
-- Un trajet bateau au m³ n'a pas de kilos : available_kg, total_kg et price_per_kg deviennent
-- facultatifs, mais seulement pour lui (chk_announcements_kg_or_m3) ; tout autre trajet les garde.
--
-- Rien sur chk_announcements_arrival_date ni sur les modèles et séries : V379 a déjà élargi
-- l'arrivée à 120 jours, et les modèles et séries bateau sont refusés côté service
-- (boat-template-unsupported).

-- 1. Trajet : date d'arrivée la plus tardive (fourchette), capacité et prix en m³.
--    La fourchette n'a de sens qu'avec une date d'arrivée au plus tôt, et ne dépasse pas
--    départ + 120 jours (même plafond que l'arrivée).
ALTER TABLE announcements
    ADD COLUMN arrival_date_max DATE,
    ADD COLUMN volume_capacity_m3 NUMERIC(8,3),
    ADD COLUMN available_volume_m3 NUMERIC(8,3),
    ADD COLUMN price_per_m3 NUMERIC(10,2);

ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_arrival_date_max
        CHECK (arrival_date_max IS NULL
               OR (arrival_date IS NOT NULL
                   AND arrival_date_max >= arrival_date
                   AND arrival_date_max <= departure_date + 120));

-- Volume : capacité et volume restant vont ensemble (tous deux nuls ou tous deux renseignés),
-- capacité strictement positive, restant entre 0 et la capacité ; prix au m³ strictement positif,
-- sans borne haute (le plafond est celui de la colonne).
ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_volume
        CHECK (((volume_capacity_m3 IS NULL AND available_volume_m3 IS NULL)
                OR (volume_capacity_m3 IS NOT NULL AND available_volume_m3 IS NOT NULL
                    AND volume_capacity_m3 > 0
                    AND available_volume_m3 >= 0
                    AND available_volume_m3 <= volume_capacity_m3))
               AND (price_per_m3 IS NULL OR price_per_m3 > 0));

-- Un trajet bateau au m³ n'a pas de kilos : capacité, capacité initiale et prix au kilo deviennent
-- facultatifs (aucune valeur factice). Les lignes existantes gardent leurs valeurs.
ALTER TABLE announcements
    ALTER COLUMN available_kg DROP NOT NULL,
    ALTER COLUMN total_kg DROP NOT NULL,
    ALTER COLUMN price_per_kg DROP NOT NULL;

-- Les deux CHECK des kilos écrits explicitement pour NULL (même règle qu'avant pour une valeur).
ALTER TABLE announcements DROP CONSTRAINT IF EXISTS chk_announcements_available_kg;
ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_available_kg
        CHECK (available_kg IS NULL OR available_kg >= 0);

ALTER TABLE announcements DROP CONSTRAINT IF EXISTS chk_announcements_price_per_kg;
ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_price_per_kg
        CHECK (price_per_kg IS NULL
               OR (price_per_kg >= 0 AND (pricing_mode = 'MIXED' OR price_per_kg > 0)));

-- Unité du trajet : au kilo (tous modes : capacité, capacité initiale et prix au kilo renseignés,
-- pas de prix au m³) OU au m³ (bateau seulement : prix au m³ et volume renseignés, aucun kilo).
-- Seul un trajet bateau au m³ peut donc avoir des kilos nuls ; tout autre trajet garde les trois.
ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_kg_or_m3
        CHECK ((price_per_m3 IS NULL
                AND available_kg IS NOT NULL AND total_kg IS NOT NULL AND price_per_kg IS NOT NULL)
               OR (price_per_m3 IS NOT NULL AND transport_mode = 'BOAT'
                   AND available_kg IS NULL AND total_kg IS NULL AND price_per_kg IS NULL
                   AND volume_capacity_m3 IS NOT NULL AND available_volume_m3 IS NOT NULL));

-- Fourchette d'arrivée et volume : bateau seulement (un trajet passé à un autre mode les efface).
ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_boat_columns
        CHECK (transport_mode = 'BOAT'
               OR (arrival_date_max IS NULL AND volume_capacity_m3 IS NULL AND available_volume_m3 IS NULL));

-- 2. Demande de colis : dimensions en cm (toutes renseignées ou toutes nulles) et volume en m³.
--    Les IS NOT NULL sont nécessaires : un BETWEEN sur NULL vaut NULL et un CHECK laisse passer NULL.
ALTER TABLE package_requests
    ADD COLUMN length_cm INTEGER,
    ADD COLUMN width_cm INTEGER,
    ADD COLUMN height_cm INTEGER,
    ADD COLUMN volume_m3 NUMERIC(8,3);

ALTER TABLE package_requests
    ADD CONSTRAINT chk_package_requests_dimensions
        CHECK ((length_cm IS NULL AND width_cm IS NULL AND height_cm IS NULL)
               OR (length_cm IS NOT NULL AND width_cm IS NOT NULL AND height_cm IS NOT NULL
                   AND length_cm BETWEEN 1 AND 400
                   AND width_cm BETWEEN 1 AND 400
                   AND height_cm BETWEEN 1 AND 400));

ALTER TABLE package_requests
    ADD CONSTRAINT chk_package_requests_volume
        CHECK (volume_m3 IS NULL OR volume_m3 > 0);

-- 3. Réservation : volume réservé en m³.
ALTER TABLE bids
    ADD COLUMN volume_m3 NUMERIC(8,3);

ALTER TABLE bids
    ADD CONSTRAINT chk_bids_volume
        CHECK (volume_m3 IS NULL OR volume_m3 > 0);

-- 4. Retards de traversée déclarés par le voyageur (fourchette avant et après, motif, message).
CREATE TABLE trip_delays (
    id                         UUID PRIMARY KEY,
    announcement_id            UUID NOT NULL REFERENCES announcements(id),
    reason                     VARCHAR(32) NOT NULL,
    previous_arrival_date      DATE,
    previous_arrival_date_max  DATE,
    new_arrival_date           DATE NOT NULL,
    new_arrival_date_max       DATE NOT NULL,
    message                    VARCHAR(300),
    created_by                 UUID NOT NULL REFERENCES users(id),
    created_at                 TIMESTAMP NOT NULL,
    updated_at                 TIMESTAMP NOT NULL,
    deleted_at                 TIMESTAMP,
    CONSTRAINT chk_trip_delays_reason
        CHECK (reason IN ('PORT_CONGESTION', 'WEATHER', 'EXTENDED_STOPOVER', 'TRANSPORT_DISRUPTION', 'OTHER')),
    CONSTRAINT chk_trip_delays_range
        CHECK (new_arrival_date_max >= new_arrival_date)
);
CREATE INDEX idx_trip_delays_announcement ON trip_delays (announcement_id, created_at DESC);
