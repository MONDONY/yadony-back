CREATE TABLE exchange_rates (
    currency        VARCHAR(3) PRIMARY KEY,
    units_per_eur   NUMERIC(18, 6) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID,
    CONSTRAINT chk_exchange_rates_currency CHECK (
        currency IN ('EUR','USD','CAD','GBP','CHF','XOF','XAF')),
    CONSTRAINT chk_exchange_rates_positive CHECK (units_per_eur > 0)
);

-- Amorçage depuis les valeurs jusqu'ici en dur dans SupportedCurrency.
INSERT INTO exchange_rates (currency, units_per_eur) VALUES
    ('EUR', 1), ('USD', 1.08), ('CAD', 1.47), ('GBP', 0.86),
    ('CHF', 0.95), ('XOF', 655.957), ('XAF', 655.957);
