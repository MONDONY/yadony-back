-- V317 : résultat de la dernière vérification d'une alerte prix vols.
--
-- last_match_count    : nombre d'offres de la dernière vérification (fenêtre de l'alerte, filtre
--                       direct, devise) dont le prix est sous le seuil ; NULL tant que l'alerte n'a
--                       jamais été vérifiée, 0 quand plus rien n'est sous le seuil.
-- last_match_airlines : jusqu'à trois codes IATA distincts de ces offres, par prix croissant,
--                       séparés par des virgules (ex. « AF,SS,TU ») ; vide quand rien n'est sous le seuil.

ALTER TABLE flight_price_alerts
    ADD COLUMN last_match_count    INTEGER,
    ADD COLUMN last_match_airlines VARCHAR(32);

ALTER TABLE flight_price_alerts ADD CONSTRAINT ck_flight_price_alerts_match_count
    CHECK (last_match_count IS NULL OR last_match_count >= 0);
