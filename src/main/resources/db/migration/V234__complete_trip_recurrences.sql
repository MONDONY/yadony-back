ALTER TABLE trip_recurrences
    ADD COLUMN start_date DATE,
    ADD COLUMN end_date DATE,
    ADD COLUMN week_interval INTEGER,
    ADD COLUMN publication_lead_days INTEGER,
    ADD COLUMN handover_lead_days INTEGER,
    ADD COLUMN pricing_mode VARCHAR(10),
    ADD COLUMN negotiable BOOLEAN,
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN refused_categories TEXT,
    ADD COLUMN description VARCHAR(500),
    ADD COLUMN last_publication_error_code VARCHAR(100),
    ADD COLUMN last_publication_error_message VARCHAR(255),
    ADD COLUMN last_publication_error_at TIMESTAMP;

UPDATE trip_recurrences
SET start_date = COALESCE(created_at::date, CURRENT_DATE),
    week_interval = 1,
    publication_lead_days = COALESCE(horizon_days, 14),
    handover_lead_days = 0,
    pricing_mode = 'KG',
    negotiable = FALSE,
    currency = 'EUR';

ALTER TABLE trip_recurrences
    ALTER COLUMN start_date SET NOT NULL,
    ALTER COLUMN start_date SET DEFAULT CURRENT_DATE,
    ALTER COLUMN week_interval SET NOT NULL,
    ALTER COLUMN week_interval SET DEFAULT 1,
    ALTER COLUMN publication_lead_days SET NOT NULL,
    ALTER COLUMN publication_lead_days SET DEFAULT 14,
    ALTER COLUMN handover_lead_days SET NOT NULL,
    ALTER COLUMN handover_lead_days SET DEFAULT 0,
    ALTER COLUMN pricing_mode SET NOT NULL,
    ALTER COLUMN pricing_mode SET DEFAULT 'KG',
    ALTER COLUMN negotiable SET NOT NULL,
    ALTER COLUMN negotiable SET DEFAULT FALSE,
    ALTER COLUMN currency SET NOT NULL,
    ALTER COLUMN currency SET DEFAULT 'EUR',
    ADD CONSTRAINT chk_trip_recurrences_period
        CHECK (end_date IS NULL OR end_date >= start_date),
    ADD CONSTRAINT chk_trip_recurrences_week_interval
        CHECK (week_interval BETWEEN 1 AND 4),
    ADD CONSTRAINT chk_trip_recurrences_publication_lead
        CHECK (publication_lead_days BETWEEN 1 AND 60),
    ADD CONSTRAINT chk_trip_recurrences_handover_lead
        CHECK (handover_lead_days BETWEEN 0 AND 3),
    ADD CONSTRAINT chk_trip_recurrences_pricing_mode
        CHECK (pricing_mode IN ('KG', 'MIXED')),
    ADD CONSTRAINT chk_trip_recurrences_currency
        CHECK (currency = upper(currency) AND char_length(currency) = 3);

ALTER TABLE announcements
    ADD COLUMN source_recurrence_id UUID,
    ADD CONSTRAINT announcements_source_recurrence_fkey
        FOREIGN KEY (source_recurrence_id) REFERENCES trip_recurrences(id);

CREATE UNIQUE INDEX uq_announcements_recurrence_departure
    ON announcements(source_recurrence_id, departure_date)
    WHERE source_recurrence_id IS NOT NULL;
