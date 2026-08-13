-- Devise du crédit de parrainage.
--
-- La récompense est désormais versée dans la devise active du parrain au moment
-- du versement, sans conversion : un parrain travaillant en dollar reçoit
-- 5 USD sur son portefeuille en dollars, là où tout le monde était auparavant
-- crédité en euros et se retrouvait avec une somme dépensable uniquement sur des
-- transactions en euros.
--
-- Sans cette colonne, sumAmountCentsByUserId additionnait des montants de
-- devises différentes en un seul total : 5 USD puis 5 EUR donnaient « 10 » sans
-- qu'aucune devise ne puisse être affichée honnêtement.
--
-- Les crédits existants ont tous été versés en euros : le défaut reflète donc
-- l'historique réel et n'invente rien.

ALTER TABLE user_credits
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR';

ALTER TABLE user_credits
    ADD CONSTRAINT chk_user_credits_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));

CREATE INDEX IF NOT EXISTS idx_user_credits_user_currency
    ON user_credits (user_id, currency);
