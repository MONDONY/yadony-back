-- Uniformise la casse des codes devise du portefeuille.
--
-- wallet_accounts porte UNIQUE(user_id, currency) depuis V201, mais aucune
-- contrainte sur la forme du code. Or deux conventions coexistent dans le code :
-- les tables métier stockent 'EUR' (CHECK des migrations V198 à V200) alors que
-- SupportedCurrency.code() renvoie 'eur', la forme attendue par Stripe.
--
-- Un appelant passant 'eur' aurait donc ouvert un SECOND portefeuille pour une
-- devise déjà détenue : l'unicité compare des chaînes et ne voit pas que les
-- deux valeurs désignent la même devise. Le solde se serait retrouvé éclaté sur
-- deux comptes, avec des débits refusés pour solde insuffisant alors que
-- l'argent était présent.
--
-- On normalise l'existant puis on ferme la porte par un CHECK, pour que la règle
-- ne repose plus uniquement sur la discipline du code applicatif.

-- Fusionne d'abord les éventuels doublons de casse : le compte le plus ancien
-- absorbe le solde des autres, qui sont ensuite supprimés.
WITH ranked AS (
    SELECT id,
           user_id,
           UPPER(currency) AS norm_currency,
           balance,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, UPPER(currency)
               ORDER BY created_at
           ) AS rn
    FROM wallet_accounts
),
survivors AS (
    SELECT user_id, norm_currency, id AS keep_id
    FROM ranked
    WHERE rn = 1
),
merged AS (
    SELECT s.keep_id, COALESCE(SUM(r.balance), 0) AS total
    FROM survivors s
             JOIN ranked r
                  ON r.user_id = s.user_id
                      AND r.norm_currency = s.norm_currency
    GROUP BY s.keep_id
)
UPDATE wallet_accounts w
SET balance = m.total
FROM merged m
WHERE w.id = m.keep_id;

DELETE FROM wallet_accounts w
WHERE EXISTS (SELECT 1
              FROM wallet_accounts o
              WHERE o.user_id = w.user_id
                AND UPPER(o.currency) = UPPER(w.currency)
                AND o.created_at < w.created_at);

UPDATE wallet_accounts SET currency = UPPER(currency) WHERE currency <> UPPER(currency);
UPDATE wallet_transactions SET currency = UPPER(currency) WHERE currency <> UPPER(currency);

ALTER TABLE wallet_accounts
    ADD CONSTRAINT chk_wallet_accounts_currency
        CHECK (currency IN ('EUR', 'USD', 'CAD', 'GBP', 'CHF', 'XOF', 'XAF'));
