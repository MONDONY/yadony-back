-- Réseaux mobile money acceptés par le voyageur sur son numéro de versement : codes pawaPay
-- séparés par des virgules, dans l'ordre du catalogue (ex. 'ORANGE_CIV,WAVE_CIV').
-- mobile_money_provider reste le réseau de repli du versement (détecté s'il est coché, sinon le
-- premier coché). Les comptes existants n'acceptent que leur opérateur prédit jusqu'à ce que le
-- voyageur élargisse depuis l'app.
ALTER TABLE users ADD COLUMN mobile_money_providers VARCHAR(255);

UPDATE users
SET mobile_money_providers = mobile_money_provider
WHERE mobile_money_provider IS NOT NULL;
