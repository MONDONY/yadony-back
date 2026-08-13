ALTER TABLE user_business_preferences
    DROP CONSTRAINT chk_currency,
    ADD CONSTRAINT chk_currency CHECK (currency_code IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));
