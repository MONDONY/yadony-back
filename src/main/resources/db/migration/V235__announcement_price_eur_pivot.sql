-- Pivot EUR du prix au kilo (Tâche 10 complétée : marché unifié, comparaisons converties).
--
-- Le fil mélange des annonces EUR/XOF/USD : filtrer ou trier sur price_per_kg brut
-- n'a aucun sens entre devises. On matérialise l'équivalent EUR (4 décimales, taux
-- de exchange_rates) pour que filtre « prix max », tri par prix, score de matching
-- et estimation de corridor opèrent en SQL sur une échelle commune.
--
-- Entretien : posé à l'écriture par AnnouncementService/NegotiationService, et
-- recalculé en masse par AdminExchangeRateController à chaque changement de taux.
ALTER TABLE announcements ADD COLUMN price_per_kg_eur NUMERIC(19, 4);

UPDATE announcements
SET price_per_kg_eur = ROUND(price_per_kg / (
        SELECT e.units_per_eur FROM exchange_rates e
        WHERE e.currency = UPPER(announcements.currency)), 4)
WHERE price_per_kg IS NOT NULL;

CREATE INDEX idx_announcements_price_per_kg_eur ON announcements (price_per_kg_eur);
