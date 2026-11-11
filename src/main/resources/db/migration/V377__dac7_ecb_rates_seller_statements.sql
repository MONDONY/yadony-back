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

-- Fichiers DPI-DAC7 GÉNÉRÉS (le dépôt Télé-TD reste un geste humain) : un run par génération. Le numéro d'ordre DGFiP
-- ne se réemploie jamais pour une même année (UNIQUE). Un run devient DEPOSITED seulement quand l'équipe confirme
-- l'avoir déposé : un fichier jamais reçu (504) ou jamais déposé n'écarte aucun voyageur des exports suivants.
CREATE TABLE dac7_export_runs (
    id             UUID                     NOT NULL,
    reporting_year INTEGER                  NOT NULL,
    sequence       INTEGER                  NOT NULL,
    export_mode    VARCHAR(10)              NOT NULL,
    status         VARCHAR(10)              NOT NULL,
    file_name      VARCHAR(120)             NOT NULL,
    sellers        INTEGER                  NOT NULL,
    generated_by   UUID,
    generated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deposited_by   UUID,
    deposited_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_dac7_export_runs PRIMARY KEY (id),
    CONSTRAINT uq_dac7_export_runs_year_sequence UNIQUE (reporting_year, sequence),
    CONSTRAINT chk_dac7_export_runs_status CHECK (status IN ('GENERATED', 'DEPOSITED')),
    CONSTRAINT chk_dac7_export_runs_mode CHECK (export_mode IN ('FULL', 'PARTIAL')),
    CONSTRAINT chk_dac7_export_runs_year CHECK (reporting_year BETWEEN 2023 AND 2100),
    CONSTRAINT chk_dac7_export_runs_sequence CHECK (sequence BETWEEN 0 AND 999),
    CONSTRAINT chk_dac7_export_runs_deposit CHECK (status = 'GENERATED' OR deposited_at IS NOT NULL)
);

COMMENT ON TABLE dac7_export_runs IS
    'Fichiers DAC7 générés (ordre DGFiP unique par année) ; DEPOSITED seulement après confirmation de l''équipe.';

-- Voyageurs de chaque run : retenu (INCLUDED, avec sa référence DocRefId déterministe, recalculable pour une
-- rectificative) ou écarté (EXCLUDED, avec ses raisons, codes séparés par des virgules). Un export PARTIAL écarte par
-- défaut les voyageurs retenus dans un run DÉPOSÉ de l'année. Aucune donnée personnelle.
CREATE TABLE dac7_export_sellers (
    id             BIGSERIAL                NOT NULL,
    reporting_year INTEGER                  NOT NULL,
    user_id        UUID                     NOT NULL,
    export_run_id  UUID                     NOT NULL,
    status         VARCHAR(10)              NOT NULL,
    doc_ref_id     VARCHAR(200),
    reason         VARCHAR(255),
    sequence       INTEGER                  NOT NULL,
    export_mode    VARCHAR(10)              NOT NULL,
    exported_by    UUID,
    exported_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_dac7_export_sellers PRIMARY KEY (id),
    CONSTRAINT fk_dac7_export_sellers_run FOREIGN KEY (export_run_id) REFERENCES dac7_export_runs (id),
    CONSTRAINT chk_dac7_export_sellers_status CHECK (status IN ('INCLUDED', 'EXCLUDED')),
    CONSTRAINT chk_dac7_export_sellers_mode CHECK (export_mode IN ('FULL', 'PARTIAL')),
    CONSTRAINT chk_dac7_export_sellers_year CHECK (reporting_year BETWEEN 2023 AND 2100),
    CONSTRAINT chk_dac7_export_sellers_ref CHECK (status = 'EXCLUDED' OR doc_ref_id IS NOT NULL)
);

CREATE INDEX idx_dac7_export_sellers_year_user ON dac7_export_sellers (reporting_year, user_id);
CREATE INDEX idx_dac7_export_sellers_run ON dac7_export_sellers (export_run_id);

COMMENT ON TABLE dac7_export_sellers IS
    'Voyageurs de chaque fichier DAC7 généré (retenus avec DocRefId, ou écartés avec raisons). Sans donnée personnelle.';
