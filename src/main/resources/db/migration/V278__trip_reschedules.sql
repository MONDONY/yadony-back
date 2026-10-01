-- Report d'un trajet publié (vol annulé, voyage repoussé). Le voyageur change la
-- date ; chaque expéditeur dont le colis est accepté ou déjà remis choisit de le
-- garder sur la nouvelle date ou de se retirer sans frais.

CREATE TABLE trip_reschedules (
    id                          UUID PRIMARY KEY,
    announcement_id             UUID NOT NULL REFERENCES announcements(id),
    traveler_id                 UUID NOT NULL REFERENCES users(id),
    reason                      VARCHAR(20) NOT NULL,
    note                        VARCHAR(300),
    previous_departure_date     DATE NOT NULL,
    previous_departure_time     TIME,
    previous_arrival_date       DATE,
    previous_arrival_time       TIME,
    previous_handover_deadline  TIMESTAMP,
    new_departure_date          DATE NOT NULL,
    new_departure_time          TIME,
    new_arrival_date            DATE,
    new_arrival_time            TIME,
    new_handover_deadline       TIMESTAMP,
    created_at                  TIMESTAMP NOT NULL,
    updated_at                  TIMESTAMP,
    deleted_at                  TIMESTAMP,
    CONSTRAINT chk_trip_reschedules_reason
        CHECK (reason IN ('FLIGHT_CANCELLED', 'POSTPONED', 'OTHER'))
);
CREATE INDEX idx_trip_reschedules_announcement ON trip_reschedules (announcement_id, created_at DESC);

-- Deux reports au plus par trajet : au-delà, le voyageur annule et republie.
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS reschedule_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE announcements ADD CONSTRAINT chk_announcements_reschedule_count
    CHECK (reschedule_count BETWEEN 0 AND 2);

-- Report auquel l'expéditeur n'a pas encore répondu. NULL = rien à décider.
ALTER TABLE bids ADD COLUMN IF NOT EXISTS pending_reschedule_id UUID REFERENCES trip_reschedules(id);
