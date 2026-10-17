-- Compteurs du jour (UTC) des fournisseurs d'IA de l'assistant : nombre d'essais réservés et de
-- réponses réussies. Gardés en base pour survivre aux redémarrages : la colonne « Aujourd'hui » de
-- l'admin et la limite par jour (assistant_llm_providers.daily_request_limit) en dépendent.
-- Table technique de compteurs : pas de soft delete, une ligne par fournisseur et par jour.

CREATE TABLE assistant_provider_daily_usage (
    provider_id UUID      NOT NULL REFERENCES assistant_llm_providers(id),
    usage_date  DATE      NOT NULL,
    requests    INTEGER   NOT NULL DEFAULT 0,
    successes   INTEGER   NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT pk_assistant_provider_daily_usage PRIMARY KEY (provider_id, usage_date),
    CONSTRAINT chk_assistant_provider_daily_usage_counts CHECK (requests >= 0 AND successes >= 0)
);

-- Lecture des stats admin : tous les fournisseurs pour un jour donné, en une requête.
CREATE INDEX idx_assistant_provider_daily_usage_date
    ON assistant_provider_daily_usage (usage_date);
