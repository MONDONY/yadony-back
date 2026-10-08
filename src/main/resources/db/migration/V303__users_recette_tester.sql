-- FLUTTER-FA / FLUTTER-FB : comptes testeurs du mode « recette » (staging uniquement).
--
-- Un testeur peut, quand yadony.recette.enabled est vrai et hors profil prod :
--   - valider la livraison d'un colis avant le départ du trajet ;
--   - être rattaché comme destinataire de son propre colis.
-- Le drapeau seul ne suffit pas : RecetteMode exige aussi la propriété, fausse par défaut et
-- ignorée sous le profil prod. Seul un administrateur le modifie
-- (PUT /admin/users/{id}/recette-tester), chaque changement est tracé dans audit_log.

ALTER TABLE users ADD COLUMN IF NOT EXISTS recette_tester BOOLEAN NOT NULL DEFAULT FALSE;
