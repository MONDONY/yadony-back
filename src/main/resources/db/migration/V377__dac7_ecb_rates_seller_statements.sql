-- V377 : DAC7, lot 3b. Taux de référence BCE par jour et récapitulatifs annuels envoyés aux voyageurs.
--
-- Note sur V376 (jamais modifiée) : son en-tête dit que le pays de résidence fiscale « n'est pas recopié » sur users.
-- C'est vrai pour un compte actif ; pour un compte supprimé dont le NIF est gardé, V376 fige bien ce pays
-- (tax_residence_country) et le statut du vendeur (tax_seller_status), que l'export du lot 3b lit en priorité.

-- Taux de référence de la BCE (eurofxref-hist.xml), en unités de la devise pour un euro, un par devise et par jour
-- de publication. Cache local : l'export lit cette table, jamais le réseau. Le franc CFA (XOF, XAF) n'y figure pas
-- (parité fixe, que la BCE ne publie pas). Un jour sans ligne n'est jamais deviné.
CREATE TABLE ecb_exchange_rates (
    id            BIGSERIAL                NOT NULL,
    currency      VARCHAR(3)               NOT NULL,
    rate_date     DATE                     NOT NULL,
    units_per_eur NUMERIC(18, 6)           NOT NULL,
    fetched_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_ecb_exchange_rates PRIMARY KEY (id),
    CONSTRAINT uq_ecb_exchange_rates UNIQUE (currency, rate_date),
    CONSTRAINT chk_ecb_exchange_rates_rate CHECK (units_per_eur > 0),
    CONSTRAINT chk_ecb_exchange_rates_currency CHECK (currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE ecb_exchange_rates IS
    'Taux de référence BCE par jour (DAC7, conversion en euros). Cache de eurofxref-hist, jamais deviné.';

-- Récapitulatif annuel DAC7 envoyé au voyageur (art. 1649 ter D, III du CGI), seulement à la demande de l'équipe :
-- une ligne par voyageur et par année, posée avant l'envoi, garantit qu'il ne part qu'une fois. Aucun montant ni
-- aucune donnée d'identité ici. Pas de clé étrangère vers users, comme le registre des diligences.
CREATE TABLE dac7_seller_statements (
    id             BIGSERIAL                NOT NULL,
    user_id        UUID                     NOT NULL,
    reporting_year INTEGER                  NOT NULL,
    sent_by        UUID,
    sent_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_dac7_seller_statements PRIMARY KEY (id),
    CONSTRAINT uq_dac7_seller_statements_user_year UNIQUE (user_id, reporting_year),
    CONSTRAINT chk_dac7_seller_statements_year CHECK (reporting_year BETWEEN 2023 AND 2100)
);

COMMENT ON TABLE dac7_seller_statements IS
    'Récapitulatifs DAC7 envoyés aux voyageurs : un par voyageur et par année, envoi décidé par l''équipe.';

-- Voyageurs de chaque fichier DPI-DAC7 GÉNÉRÉ (le dépôt Télé-TD reste un geste humain) : une ligne par voyageur et par
-- génération, retenu (INCLUDED, avec sa référence DocRefId déterministe, recalculable pour une rectificative) ou écarté
-- (EXCLUDED, avec sa raison). Un second export PARTIAL de la même année écarte par défaut les voyageurs déjà retenus.
-- Aucune donnée personnelle : identifiant, codes et références seulement.
CREATE TABLE dac7_export_sellers (
    id             BIGSERIAL                NOT NULL,
    reporting_year INTEGER                  NOT NULL,
    user_id        UUID                     NOT NULL,
    export_run_id  UUID                     NOT NULL,
    status         VARCHAR(10)              NOT NULL,
    doc_ref_id     VARCHAR(200),
    reason         VARCHAR(40),
    sequence       INTEGER                  NOT NULL,
    export_mode    VARCHAR(10)              NOT NULL,
    exported_by    UUID,
    exported_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_dac7_export_sellers PRIMARY KEY (id),
    CONSTRAINT chk_dac7_export_sellers_status CHECK (status IN ('INCLUDED', 'EXCLUDED')),
    CONSTRAINT chk_dac7_export_sellers_mode CHECK (export_mode IN ('FULL', 'PARTIAL')),
    CONSTRAINT chk_dac7_export_sellers_year CHECK (reporting_year BETWEEN 2023 AND 2100),
    CONSTRAINT chk_dac7_export_sellers_ref CHECK (status = 'EXCLUDED' OR doc_ref_id IS NOT NULL)
);

CREATE INDEX idx_dac7_export_sellers_year_user ON dac7_export_sellers (reporting_year, user_id);

COMMENT ON TABLE dac7_export_sellers IS
    'Voyageurs de chaque fichier DAC7 généré (retenus avec DocRefId, ou écartés avec raison). Sans donnée personnelle.';
