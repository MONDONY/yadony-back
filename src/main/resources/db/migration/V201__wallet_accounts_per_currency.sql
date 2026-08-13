ALTER TABLE wallet_accounts
    DROP CONSTRAINT wallet_accounts_user_id_unique;

ALTER TABLE wallet_accounts
    ADD CONSTRAINT wallet_accounts_user_id_currency_unique UNIQUE (user_id, currency);
