ALTER TABLE bids
    ADD COLUMN return_expired_notified_at TIMESTAMP;

CREATE INDEX idx_bids_return_expired_notification_due
    ON bids (return_deadline)
    WHERE returned_at IS NULL AND return_expired_notified_at IS NULL;
