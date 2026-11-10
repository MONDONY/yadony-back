-- Modals : moments d'action (étape 2).
--
-- Les nouveaux déclencheurs (DELIVERY_CONFIRMED, SEARCH_NO_TRIP_RESULTS…) vivent dans la colonne
-- JSON « triggers » existante : aucune contrainte à toucher. Les deux colonnes ajoutées portent un
-- défaut qui reproduit le comportement d'avant : seuils par défaut (2 résultats, 3 consultations) et
-- aucun corridor (la campagne vaut pour tous).
-- {"fewResultsMax":2,"tripViewedTimes":3} : « peu de résultats » (1 à 20) et « même trajet consulté N fois » (2 à 10).
ALTER TABLE video_modal_campaigns
    ADD COLUMN trigger_options TEXT NOT NULL DEFAULT '{"fewResultsMax":2,"tripViewedTimes":3}';
-- [{"from":"Paris","to":"Abidjan"}] : côté nul = n'importe lequel ; liste vide = tous les corridors.
ALTER TABLE video_modal_campaigns ADD COLUMN corridors TEXT NOT NULL DEFAULT '[]';
