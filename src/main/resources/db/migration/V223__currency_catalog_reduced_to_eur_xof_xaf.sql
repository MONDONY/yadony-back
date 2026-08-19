-- Réduit le catalogue de devises à EUR/XOF/XAF (zéro compte réel en production le
-- 2026-08-19, décision produit — voir docs/specs/2026-08-19-plan-implementation-multidevise.md).
--
-- SupportedCurrency.fromCodeOrDefault() dégrade en EUR EN SILENCE pour tout code
-- absent du catalogue. Sans cette migration, une ligne résiduelle en USD/CAD/GBP/CHF
-- serait lue comme EUR par le code applicatif tout en restant écrite USD/CAD/GBP/CHF
-- en base : le CHECK ci-dessous rend visible et refuse toute nouvelle divergence.
--
-- Normalise d'abord les données (les 8 colonnes qui portaient un CHECK sur les
-- 7 devises), puis resserre chaque contrainte. payments.currency, chargebacks.currency
-- et la table mobile money n'ont jamais eu de CHECK : elles reflètent une valeur
-- fournie par Stripe / le rail mobile money, pas un choix de catalogue interne — on
-- les laisse telles quelles.

UPDATE user_business_preferences SET currency_code = 'EUR'
    WHERE currency_code NOT IN ('EUR', 'XOF', 'XAF');
UPDATE announcements SET currency = 'EUR'
    WHERE currency NOT IN ('EUR', 'XOF', 'XAF');
UPDATE package_requests SET currency = 'EUR'
    WHERE currency NOT IN ('EUR', 'XOF', 'XAF');
UPDATE bids SET currency = 'EUR'
    WHERE currency NOT IN ('EUR', 'XOF', 'XAF');
UPDATE negotiation_threads SET currency = 'EUR'
    WHERE currency NOT IN ('EUR', 'XOF', 'XAF');
UPDATE wallet_transactions SET currency = 'EUR'
    WHERE currency NOT IN ('EUR', 'XOF', 'XAF');
UPDATE wallet_accounts SET currency = 'EUR'
    WHERE currency NOT IN ('EUR', 'XOF', 'XAF');
UPDATE user_credits SET currency = 'EUR'
    WHERE currency NOT IN ('EUR', 'XOF', 'XAF');

ALTER TABLE user_business_preferences
    DROP CONSTRAINT chk_currency,
    ADD CONSTRAINT chk_currency CHECK (currency_code IN ('EUR', 'XOF', 'XAF'));

ALTER TABLE announcements
    DROP CONSTRAINT chk_announcements_currency,
    ADD CONSTRAINT chk_announcements_currency CHECK (currency IN ('EUR', 'XOF', 'XAF'));

ALTER TABLE package_requests
    DROP CONSTRAINT chk_package_requests_currency,
    ADD CONSTRAINT chk_package_requests_currency CHECK (currency IN ('EUR', 'XOF', 'XAF'));

ALTER TABLE bids
    DROP CONSTRAINT chk_bids_currency,
    ADD CONSTRAINT chk_bids_currency CHECK (currency IN ('EUR', 'XOF', 'XAF'));

ALTER TABLE negotiation_threads
    DROP CONSTRAINT chk_negotiation_threads_currency,
    ADD CONSTRAINT chk_negotiation_threads_currency CHECK (currency IN ('EUR', 'XOF', 'XAF'));

ALTER TABLE wallet_transactions
    DROP CONSTRAINT chk_wallet_transactions_currency,
    ADD CONSTRAINT chk_wallet_transactions_currency CHECK (currency IN ('EUR', 'XOF', 'XAF'));

ALTER TABLE wallet_accounts
    DROP CONSTRAINT chk_wallet_accounts_currency,
    ADD CONSTRAINT chk_wallet_accounts_currency CHECK (currency IN ('EUR', 'XOF', 'XAF'));

ALTER TABLE user_credits
    DROP CONSTRAINT chk_user_credits_currency,
    ADD CONSTRAINT chk_user_credits_currency CHECK (currency IN ('EUR', 'XOF', 'XAF'));
