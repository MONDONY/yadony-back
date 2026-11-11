-- Socle des modes de transport (lot 0). Une seule migration pour tout le lot :
-- les durées d'arrivée par mode, la demande de colis sans mode imposé, le motif de report
-- « transport annulé » et les garde-fous sur le mode des modèles et des séries.

-- 1. Durée d'arrivée. Avion/train/autre 3 jours, voiture/bus 14, bateau 120 : le CHECK de la
--    base porte le plafond le plus large, la limite par mode est dans ArrivalRules.
ALTER TABLE announcements DROP CONSTRAINT IF EXISTS chk_announcements_arrival_date;
ALTER TABLE announcements ADD CONSTRAINT chk_announcements_arrival_date
    CHECK (arrival_date IS NULL OR (arrival_date >= departure_date AND arrival_date <= departure_date + 120));

ALTER TABLE trip_templates DROP CONSTRAINT IF EXISTS chk_trip_templates_arrival_day_offset;
ALTER TABLE trip_templates ADD CONSTRAINT chk_trip_templates_arrival_day_offset
    CHECK (arrival_day_offset BETWEEN 0 AND 120);

ALTER TABLE trip_recurrences DROP CONSTRAINT IF EXISTS chk_trip_recurrences_arrival_day_offset;
ALTER TABLE trip_recurrences ADD CONSTRAINT chk_trip_recurrences_arrival_day_offset
    CHECK (arrival_day_offset BETWEEN 0 AND 120);

-- 2. Une demande de colis peut ne pas imposer de mode (NULL = tous les modes). Le CHECK
--    chk_pkg_req_transport_mode laisse déjà passer NULL.
ALTER TABLE package_requests ALTER COLUMN transport_mode DROP NOT NULL;

-- 3. Motif de report : le transport annulé (tout mode, pas seulement le vol).
ALTER TABLE trip_reschedules DROP CONSTRAINT IF EXISTS chk_trip_reschedules_reason;
ALTER TABLE trip_reschedules ADD CONSTRAINT chk_trip_reschedules_reason
    CHECK (reason IN ('FLIGHT_CANCELLED', 'TRANSPORT_CANCELLED', 'POSTPONED', 'OTHER'));

-- 4. Le mode des modèles et des séries n'avait aucun CHECK (annonces et demandes en ont un).
--    NOT VALID : appliqué aux nouvelles lignes et aux modifications, sans revalider l'existant
--    (les données de staging et de prod n'ont pas pu être contrôlées avant). À valider plus tard
--    avec ALTER TABLE ... VALIDATE CONSTRAINT une fois le contrôle fait.
ALTER TABLE trip_templates ADD CONSTRAINT chk_trip_templates_transport_mode
    CHECK (transport_mode IN ('PLANE', 'CAR', 'TRAIN', 'BUS', 'BOAT', 'OTHER')) NOT VALID;
ALTER TABLE trip_recurrences ADD CONSTRAINT chk_trip_recurrences_transport_mode
    CHECK (transport_mode IN ('PLANE', 'CAR', 'TRAIN', 'BUS', 'BOAT', 'OTHER')) NOT VALID;
