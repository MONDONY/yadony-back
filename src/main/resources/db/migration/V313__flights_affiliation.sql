-- V313 : billets d'avion en affiliation (Travelpayouts / Aviasales).
--
-- Yadony ne vend aucun billet : l'utilisateur réserve sur le site partenaire et Yadony
-- touche une commission. Ces tables ne gardent donc aucun prix de référence ni aucune
-- réservation, seulement ce qu'il faut pour les bons plans, les alertes et les statistiques.

-- Recherches de vols : alimentent les bons plans (trajets cherchés récemment).
CREATE TABLE flight_searches (
    id                UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID                     NOT NULL REFERENCES users (id),
    origin_code       VARCHAR(3)               NOT NULL,
    destination_code  VARCHAR(3)               NOT NULL,
    round_trip        BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_flight_searches_owner_created ON flight_searches (owner_id, created_at DESC);

-- Villes des corridors Yadony déjà converties en code IATA (iata_code NULL = aucune ville trouvée).
CREATE TABLE flight_city_codes (
    id            UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    city_key      VARCHAR(120)             NOT NULL,
    city_name     VARCHAR(120)             NOT NULL,
    iata_code     VARCHAR(3),
    country_code  VARCHAR(2),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_flight_city_codes_city_key UNIQUE (city_key)
);

-- Alertes prix : prévenir quand le prix le plus bas passe sous max_price.
CREATE TABLE flight_price_alerts (
    id                   UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id             UUID                     NOT NULL REFERENCES users (id),
    origin_code          VARCHAR(3)               NOT NULL,
    destination_code     VARCHAR(3)               NOT NULL,
    origin_label         VARCHAR(120),
    destination_label    VARCHAR(120),
    -- AAAA-MM ; NULL = toutes les dates à venir
    departure_month      VARCHAR(7),
    -- AAAA-MM ; NULL = aller simple
    return_month         VARCHAR(7),
    direct_only          BOOLEAN                  NOT NULL DEFAULT FALSE,
    max_price            NUMERIC(12, 2)           NOT NULL,
    currency             VARCHAR(3)               NOT NULL,
    active               BOOLEAN                  NOT NULL DEFAULT TRUE,
    last_seen_price      NUMERIC(12, 2),
    last_checked_at      TIMESTAMP WITH TIME ZONE,
    -- Anti-spam : dernier prix annoncé, remis à NULL quand le prix repasse au-dessus du seuil.
    last_notified_price  NUMERIC(12, 2),
    last_notified_at     TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_flight_price_alerts_max_price CHECK (max_price > 0),
    CONSTRAINT ck_flight_price_alerts_months CHECK (
        (departure_month IS NULL OR departure_month ~ '^[0-9]{4}-[0-9]{2}$')
        AND (return_month IS NULL OR return_month ~ '^[0-9]{4}-[0-9]{2}$'))
);

CREATE INDEX idx_flight_price_alerts_owner ON flight_price_alerts (owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_flight_price_alerts_active ON flight_price_alerts (active) WHERE deleted_at IS NULL AND active;

-- Clics vers le partenaire : statistiques et rapprochement des commissions (sub_id).
CREATE TABLE flight_clicks (
    id                UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID                     NOT NULL REFERENCES users (id),
    origin_code       VARCHAR(3)               NOT NULL,
    destination_code  VARCHAR(3)               NOT NULL,
    price             NUMERIC(12, 2),
    currency          VARCHAR(3),
    airline_code      VARCHAR(3),
    gate              VARCHAR(100),
    -- search | deal | alert : écran d'où vient le clic
    source            VARCHAR(16)              NOT NULL,
    sub_id            VARCHAR(32)              NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_flight_clicks_sub_id UNIQUE (sub_id),
    CONSTRAINT ck_flight_clicks_source CHECK (source IN ('search', 'deal', 'alert'))
);

CREATE INDEX idx_flight_clicks_created ON flight_clicks (created_at);
