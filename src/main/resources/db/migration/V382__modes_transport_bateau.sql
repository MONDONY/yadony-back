-- V382 : bateau (lot 3 des modes de transport). Capacité en m³, fourchette d'arrivée, dimensions du
-- colis et retards de traversée. Tout est facultatif : les lignes existantes (avion, route) restent
-- NULL et rien ne change pour elles.
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

ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_volume
        CHECK ((available_volume_m3 IS NULL OR available_volume_m3 >= 0)
               AND (price_per_m3 IS NULL OR price_per_m3 > 0));

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

-- 3. Réservation : volume réservé en m³.
ALTER TABLE bids
    ADD COLUMN volume_m3 NUMERIC(8,3);

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
        CHECK (reason IN ('PORT_CONGESTION', 'WEATHER', 'EXTENDED_STOPOVER', 'OTHER'))
);
CREATE INDEX idx_trip_delays_announcement ON trip_delays (announcement_id, created_at DESC);
