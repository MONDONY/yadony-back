-- Trajet caché : le voyageur retire un trajet qui porte des colis de la recherche et des listes
-- publiques sans changer son statut (ACTIVE ou FULL) ni toucher aux colis. Additive : tous les
-- trajets existants restent visibles (false).
ALTER TABLE announcements ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
