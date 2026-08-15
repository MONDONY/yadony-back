ALTER TABLE bids
    ADD COLUMN confirmation_code_public_enabled BOOLEAN NOT NULL DEFAULT FALSE;
