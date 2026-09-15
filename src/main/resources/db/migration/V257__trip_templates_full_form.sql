-- V257 : le modèle de trajet mémorise tout le formulaire de création.
--
-- Contexte : un modèle ne stockait que corridor, transport, capacité, poids, prix au
-- kilo, catégories acceptées, espèces et heure d'arrivée. Devise, mode de tarification,
-- moyens de paiement, négociable, refusés, note, adresses, heure de départ et délai de
-- remise devaient être ressaisis à chaque publication.
--
-- Toutes les colonnes sont nullables ou à défaut : les lignes existantes restent
-- valides sans reprise. cash_accepted est conservé en miroir de accepted_payment_methods
-- pour les builds d'app antérieurs (retrait dans une migration ultérieure).

ALTER TABLE trip_templates
    ADD COLUMN currency               VARCHAR(3),
    ADD COLUMN pricing_mode           VARCHAR(10)  NOT NULL DEFAULT 'KG',
    ADD COLUMN accepted_payment_methods TEXT       NOT NULL DEFAULT 'STRIPE',
    ADD COLUMN negotiable             BOOLEAN      NOT NULL DEFAULT false,
    ADD COLUMN refused_types          TEXT,
    ADD COLUMN description            VARCHAR(500),
    ADD COLUMN pickup_address_label   VARCHAR(500),
    ADD COLUMN pickup_lat             NUMERIC(9,6),
    ADD COLUMN pickup_lng             NUMERIC(9,6),
    ADD COLUMN delivery_address_label VARCHAR(500),
    ADD COLUMN delivery_lat           NUMERIC(9,6),
    ADD COLUMN delivery_lng           NUMERIC(9,6),
    ADD COLUMN departure_time         TIME,
    ADD COLUMN handover_lead_days     INTEGER,
    ADD COLUMN departure_country_code VARCHAR(2),
    ADD COLUMN arrival_country_code   VARCHAR(2);

-- Mode grille seule : pas de prix au kilo.
ALTER TABLE trip_templates ALTER COLUMN price_per_kg DROP NOT NULL;

UPDATE trip_templates
   SET accepted_payment_methods = CASE WHEN cash_accepted THEN 'STRIPE,CASH' ELSE 'STRIPE' END;

ALTER TABLE trip_templates
    ADD CONSTRAINT chk_trip_templates_handover_lead_days
        CHECK (handover_lead_days IS NULL OR handover_lead_days BETWEEN 0 AND 7);
