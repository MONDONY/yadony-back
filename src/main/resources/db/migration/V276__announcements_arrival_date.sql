-- FLUTTER-4E : date d'arrivée d'un trajet quand elle diffère du départ (vol de
-- nuit, escale). NULL = même jour que le départ, comme pour tous les trajets
-- existants : aucun rattrapage.
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS arrival_date DATE;
ALTER TABLE announcements ADD CONSTRAINT chk_announcements_arrival_date
    CHECK (arrival_date IS NULL OR (arrival_date >= departure_date AND arrival_date <= departure_date + 3));
