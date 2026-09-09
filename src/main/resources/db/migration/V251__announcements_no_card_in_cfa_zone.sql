-- Recette du 2026-09-09 : une annonce en XOF enregistrait la carte parmi ses moyens de
-- paiement, l'app la proposait et le checkout carte ouvrait un séquestre Stripe en euros
-- pour un montant en francs CFA. Le rail carte n'existe pas en zone CFA
-- (CurrencyPaymentRails) : on le retire des annonces XOF/XAF existantes. La colonne est
-- une liste textuelle « {STRIPE,CASH,MOBILE_MONEY} » (PaymentMethodSetConverter) ; une
-- annonce qui n'offrait que la carte garde l'espèce, toujours possible.
UPDATE announcements
SET accepted_payment_methods = CASE
        WHEN regexp_replace(accepted_payment_methods, '[{}]', '', 'g') = 'STRIPE' THEN '{CASH}'
        ELSE replace(replace(accepted_payment_methods, 'STRIPE,', ''), ',STRIPE', '')
    END
WHERE currency IN ('XOF', 'XAF')
  AND accepted_payment_methods LIKE '%STRIPE%';
