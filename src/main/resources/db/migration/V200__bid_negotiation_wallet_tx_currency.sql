ALTER TABLE bids
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR';

ALTER TABLE bids
    ADD CONSTRAINT chk_bids_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));

ALTER TABLE negotiation_threads
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR';

ALTER TABLE negotiation_threads
    ADD CONSTRAINT chk_negotiation_threads_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));

ALTER TABLE wallet_transactions
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR';

ALTER TABLE wallet_transactions
    ADD CONSTRAINT chk_wallet_transactions_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));
