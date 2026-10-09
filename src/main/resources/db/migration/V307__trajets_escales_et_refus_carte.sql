-- FLUTTER-GE / FLUTTER-GD : nombre d'escales d'un trajet en avion, optionnel.
--   NULL = non renseigné (tous les trajets existants, et tout trajet hors avion) ;
--   0 = vol direct ; 1 = une escale ; 2 = deux escales ou plus.
-- Le filtre de recherche « Direct uniquement » ne garde que stops_count = 0 ; « Max 1 escale »
-- garde aussi les trajets non renseignés (AnnouncementSpecification#maxStops).
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS stops_count SMALLINT NULL;
ALTER TABLE announcements ADD CONSTRAINT chk_announcements_stops_count
    CHECK (stops_count IS NULL OR stops_count BETWEEN 0 AND 2);

-- Les trajets récurrents recopient la valeur sur chaque occurrence générée.
ALTER TABLE trip_recurrences ADD COLUMN IF NOT EXISTS stops_count SMALLINT NULL;
ALTER TABLE trip_recurrences ADD CONSTRAINT chk_trip_recurrences_stops_count
    CHECK (stops_count IS NULL OR stops_count BETWEEN 0 AND 2);

-- FLUTTER-FT : la carte devient décochable à la publication. card_declined retient un refus
-- explicite (voyageur dont le compte Stripe Connect était actif et qui a décoché la carte),
-- pour que la réouverture automatique à la carte après un onboarding Stripe
-- (AnnouncementService#enableCardOnOpenAnnouncements, FLUTTER-DH) ne passe jamais outre.
-- FALSE pour tous les trajets existants : aucun n'a pu refuser la carte jusqu'ici.
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS card_declined BOOLEAN NOT NULL DEFAULT FALSE;
