-- Lot B : coupure administrative de la messagerie d'un utilisateur.
-- NULL = messagerie autorisée. Une échéance très lointaine matérialise un
-- mute indéfini, ce qui garde la règle Firestore à une seule comparaison.
ALTER TABLE users ADD COLUMN messaging_muted_until TIMESTAMPTZ NULL;

COMMENT ON COLUMN users.messaging_muted_until IS
  'Échéance de la coupure de messagerie décidée par la modération. NULL = pas de coupure.';
