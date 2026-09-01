-- Lot 8 multidevise : devise d'affichage (presentment currency).
-- Choisie librement par l'utilisateur, jamais verrouillee par le solde du
-- portefeuille : elle ne pilote que les equivalents convertis (« environ ») et
-- les agregats, jamais un montant transactionnel.
-- NULL = automatique : suivre la devise active (portefeuille, sinon pays, sinon EUR),
-- comportement historique conserve sans backfill.
ALTER TABLE user_business_preferences
    ADD COLUMN display_currency_code VARCHAR(3);

ALTER TABLE user_business_preferences
    ADD CONSTRAINT chk_business_prefs_display_currency
    CHECK (display_currency_code IS NULL
        OR display_currency_code IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));
