-- Le pays devient une donnee saisie. Il valait "FR" en dur (UserEntity) sans que
-- personne ne l'ait jamais choisi, ce qui faisait creer TOUS les comptes Stripe
-- Connect en France : le pays d'un compte Connect est immuable apres creation.
--
-- Le backfill est inconditionnel : aucun "FR" actuellement en base ne resulte d'une
-- declaration, ils viennent tous du defaut de l'entite. Aucun compte reel en
-- production au 2026-08-19.

ALTER TABLE users ALTER COLUMN country DROP NOT NULL;
ALTER TABLE users ALTER COLUMN country DROP DEFAULT;
UPDATE users SET country = NULL;
