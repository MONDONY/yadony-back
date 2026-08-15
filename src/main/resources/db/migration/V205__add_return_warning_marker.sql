ALTER TABLE bids
    ADD COLUMN return_warning_sent_at TIMESTAMP;

CREATE INDEX idx_bids_return_warning_due
    ON bids (return_deadline)
    WHERE returned_at IS NULL AND return_warning_sent_at IS NULL;
