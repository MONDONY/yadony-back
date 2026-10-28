-- Flou de position du suivi voyageur : rectangle lat/lon et lookup nom + pays.
CREATE INDEX IF NOT EXISTS idx_cities_lat_lon ON cities (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_cities_lower_name_country ON cities (LOWER(name), country_code);
