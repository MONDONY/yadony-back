-- Devise du versement fonds de garantie. Le montant (V165) était stocké en centimes sans
-- devise et saisi en euros par le back-office, y compris sur un litige d'un colis en
-- francs CFA. Audit du 2026-09-10 : la devise suit celle du bid du litige.
ALTER TABLE disputes ADD COLUMN IF NOT EXISTS guarantee_currency VARCHAR(3);
