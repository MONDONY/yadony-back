-- FLUTTER-4E (suite) : un modèle de trajet et une récurrence mémorisent le jour
-- d'arrivée relatif au départ (vol de nuit = 1). 0 = même jour, comportement de
-- tous les modèles existants. Plafond aligné sur chk_announcements_arrival_date.
ALTER TABLE trip_templates ADD COLUMN IF NOT EXISTS arrival_day_offset INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trip_templates ADD CONSTRAINT chk_trip_templates_arrival_day_offset
    CHECK (arrival_day_offset BETWEEN 0 AND 3);

ALTER TABLE trip_recurrences ADD COLUMN IF NOT EXISTS arrival_day_offset INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trip_recurrences ADD CONSTRAINT chk_trip_recurrences_arrival_day_offset
    CHECK (arrival_day_offset BETWEEN 0 AND 3);
