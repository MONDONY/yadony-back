-- V299 : rattrapage des codes pays ISO-2 des trajets (Sentry FLUTTER-EH).
--
-- Depuis la PR #427, TripCountryCodes déduit le code pays de la ville pour les
-- NOUVEAUX trajets. Les trajets nés avant (modèle appliqué, édition qui effaçait
-- le code, trajet dédié, récurrence) gardaient un code NULL : plus de drapeau ni
-- de recherche par pays.
--
-- Règle identique à TripCountryCodes / AnnouncementRepository#findCountryCodeByCityName :
-- ville la plus peuplée portant ce nom dans le référentiel `cities`, nom comparé
-- sans tenir compte de la casse ni des espaces de bord.
--
-- Garde-fous :
--   * seuls les codes NULL sont remplis, un code renseigné n'est jamais réécrit
--     (sauf l'inversion exacte du point 1) ;
--   * les trajets supprimés logiquement sont rattrapés aussi (pas de filtre deleted_at) ;
--   * une ville absente du référentiel laisse le code NULL ;
--   * audit_log n'est pas touché.
-- NB : `cities` est peuplée au démarrage par GeoNamesDataLoader. Sur une base
-- neuve elle est vide ici et la migration ne fait rien, ce qui est correct
-- (aucun trajet à rattraper).

-- 1) Codes intervertis par l'ancien bug (ex. trajet 76a6a3de… Abidjan=FR / Paris=CI).
--    Corrigés uniquement si les deux codes sont EXACTEMENT l'inverse de la déduction
--    depuis les villes (et que les deux pays déduits diffèrent).
WITH deduced AS (
    SELECT a.id,
           (SELECT UPPER(c.country_code) FROM cities c
             WHERE LOWER(c.name) = LOWER(TRIM(a.departure_city))
             ORDER BY c.population DESC LIMIT 1) AS dep_code,
           (SELECT UPPER(c.country_code) FROM cities c
             WHERE LOWER(c.name) = LOWER(TRIM(a.arrival_city))
             ORDER BY c.population DESC LIMIT 1) AS arr_code
      FROM announcements a
     WHERE a.departure_country_code IS NOT NULL
       AND a.arrival_country_code IS NOT NULL
)
UPDATE announcements a
   SET departure_country_code = d.dep_code,
       arrival_country_code   = d.arr_code
  FROM deduced d
 WHERE a.id = d.id
   AND d.dep_code IS NOT NULL
   AND d.arr_code IS NOT NULL
   AND d.dep_code <> d.arr_code
   AND UPPER(a.departure_country_code) = d.arr_code
   AND UPPER(a.arrival_country_code)   = d.dep_code;

-- 2) Trajets : codes NULL déduits de la ville.
UPDATE announcements a
   SET departure_country_code = (
           SELECT UPPER(c.country_code) FROM cities c
            WHERE LOWER(c.name) = LOWER(TRIM(a.departure_city))
            ORDER BY c.population DESC LIMIT 1)
 WHERE a.departure_country_code IS NULL
   AND EXISTS (SELECT 1 FROM cities c WHERE LOWER(c.name) = LOWER(TRIM(a.departure_city)));

UPDATE announcements a
   SET arrival_country_code = (
           SELECT UPPER(c.country_code) FROM cities c
            WHERE LOWER(c.name) = LOWER(TRIM(a.arrival_city))
            ORDER BY c.population DESC LIMIT 1)
 WHERE a.arrival_country_code IS NULL
   AND EXISTS (SELECT 1 FROM cities c WHERE LOWER(c.name) = LOWER(TRIM(a.arrival_city)));

-- 3) Modèles de trajet : même règle.
UPDATE trip_templates t
   SET departure_country_code = (
           SELECT UPPER(c.country_code) FROM cities c
            WHERE LOWER(c.name) = LOWER(TRIM(t.departure_city))
            ORDER BY c.population DESC LIMIT 1)
 WHERE t.departure_country_code IS NULL
   AND EXISTS (SELECT 1 FROM cities c WHERE LOWER(c.name) = LOWER(TRIM(t.departure_city)));

UPDATE trip_templates t
   SET arrival_country_code = (
           SELECT UPPER(c.country_code) FROM cities c
            WHERE LOWER(c.name) = LOWER(TRIM(t.arrival_city))
            ORDER BY c.population DESC LIMIT 1)
 WHERE t.arrival_country_code IS NULL
   AND EXISTS (SELECT 1 FROM cities c WHERE LOWER(c.name) = LOWER(TRIM(t.arrival_city)));
