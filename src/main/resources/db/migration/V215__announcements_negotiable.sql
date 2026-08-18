-- Le voyageur peut ouvrir son trajet aux propositions de prix de l'expéditeur.
-- Défaut FALSE : les trajets existants restent fermés, aucune donnée à migrer.
ALTER TABLE announcements
  ADD COLUMN negotiable BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN announcements.negotiable IS
  'Le voyageur accepte les propositions de prix (fil de negociation sur un bid).';
