-- V314 : alertes prix sur une période + clics des visiteurs.
--
-- 1. Une alerte surveille désormais une PÉRIODE de départ (et de retour pour un aller-retour),
--    pas seulement un mois entier. Les mois existants sont convertis en leur premier et dernier jour.
-- 2. Un visiteur (compte invité) peut réserver sans compte : son clic vers le partenaire est
--    enregistré sans propriétaire, pour ne pas lier une ligne users destinée à la purge.

ALTER TABLE flight_price_alerts
    ADD COLUMN departure_from DATE,
    ADD COLUMN departure_to   DATE,
    ADD COLUMN return_from    DATE,
    ADD COLUMN return_to      DATE;

UPDATE flight_price_alerts
   SET departure_from = to_date(departure_month || '-01', 'YYYY-MM-DD'),
       departure_to   = (to_date(departure_month || '-01', 'YYYY-MM-DD') + INTERVAL '1 month - 1 day')::date
 WHERE departure_month IS NOT NULL;

UPDATE flight_price_alerts
   SET return_from = to_date(return_month || '-01', 'YYYY-MM-DD'),
       return_to   = (to_date(return_month || '-01', 'YYYY-MM-DD') + INTERVAL '1 month - 1 day')::date
 WHERE return_month IS NOT NULL;

ALTER TABLE flight_price_alerts DROP CONSTRAINT ck_flight_price_alerts_months;
ALTER TABLE flight_price_alerts
    DROP COLUMN departure_month,
    DROP COLUMN return_month;

ALTER TABLE flight_price_alerts ADD CONSTRAINT ck_flight_price_alerts_periods CHECK (
    ((departure_from IS NULL) = (departure_to IS NULL))
    AND ((return_from IS NULL) = (return_to IS NULL))
    AND (departure_from IS NULL OR departure_to >= departure_from)
    AND (return_from IS NULL OR return_to >= return_from));

ALTER TABLE flight_clicks ALTER COLUMN owner_id DROP NOT NULL;
