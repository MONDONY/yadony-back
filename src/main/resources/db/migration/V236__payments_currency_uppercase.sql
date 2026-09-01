-- Aligne payments.currency sur la convention majuscule du reste du schéma.
--
-- V196 a introduit la colonne avec DEFAULT 'eur' et PaymentService écrivait
-- SupportedCurrency.code(), en minuscules — quand announcements, bids, threads,
-- wallets (V202) et user_credits stockent 'EUR'. Le piège « eur ≠ EUR » a déjà
-- éclaté une fois côté wallets (deux portefeuilles pour une même devise) ;
-- on ferme la même porte ici avant qu'un rapprochement payments↔wallet ne la
-- morde. PaymentService normalise désormais en majuscules à l'écriture.
UPDATE payments SET currency = UPPER(currency);

ALTER TABLE payments ALTER COLUMN currency SET DEFAULT 'EUR';

ALTER TABLE payments ADD CONSTRAINT chk_payments_currency
    CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));

-- Même dette côté chargebacks : la devise y est copiée brute du webhook Stripe
-- (minuscule). Pas de CHECK ici — Stripe peut ouvrir un litige dans une devise
-- hors catalogue Yadony, on normalise seulement la casse.
UPDATE chargebacks SET currency = UPPER(currency);
