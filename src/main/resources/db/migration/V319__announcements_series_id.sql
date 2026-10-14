-- Trajet récurrent par dates précises : le trajet d'origine et ses copies partagent un
-- identifiant de série. Lien informatif (pas de table « série »), null hors série.
ALTER TABLE announcements ADD COLUMN series_id UUID;

CREATE INDEX idx_announcements_series
    ON announcements (series_id, departure_date)
    WHERE series_id IS NOT NULL;
