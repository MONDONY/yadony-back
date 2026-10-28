-- Suivi approximatif du voyageur : échantillons de position floutés (ville proche + pourcentage de
-- trajet). Les coordonnées brutes ne sont jamais stockées : aucune colonne latitude/longitude.

CREATE TABLE trip_position_samples (
    id UUID PRIMARY KEY,
    announcement_id UUID NOT NULL REFERENCES announcements (id),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    city_name VARCHAR(120) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    progress_pct INTEGER,
    source VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_trip_position_samples_trip ON trip_position_samples (announcement_id, captured_at);

ALTER TABLE announcements
    ADD COLUMN departed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN departure_prompt_sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN arrival_prompt_sent_at TIMESTAMP WITH TIME ZONE;
