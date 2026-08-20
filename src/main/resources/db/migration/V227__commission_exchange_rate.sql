ALTER TABLE wallet_transactions
    ADD COLUMN source_currency VARCHAR(3),
    ADD COLUMN source_amount   NUMERIC(19, 4),
    ADD COLUMN applied_rate    NUMERIC(18, 6);
