ALTER TABLE payments
    ADD COLUMN stripe_fx_quote_id VARCHAR(255),
    ADD COLUMN fx_exchange_rate NUMERIC(20, 10),
    ADD COLUMN fx_quote_expires_at TIMESTAMP WITH TIME ZONE;
