-- Fournisseurs d'IA de l'assistant (spec docs-claude 2026-10-16-assistant-ia-support-design.md §3.8).
-- Gérés depuis l'admin ; essayés par priorité croissante. Clé chiffrée AES-256-GCM côté application.

CREATE TABLE assistant_llm_providers (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(60)  NOT NULL,
    slug                VARCHAR(40)  NOT NULL,
    base_url            VARCHAR(255) NOT NULL,
    model               VARCHAR(120) NOT NULL,
    api_key_encrypted   TEXT         NOT NULL,
    api_key_last4       VARCHAR(4)   NOT NULL,
    supports_json_mode  BOOLEAN      NOT NULL DEFAULT TRUE,
    daily_request_limit INTEGER,
    priority            INTEGER      NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    deleted_at          TIMESTAMP,
    CONSTRAINT chk_assistant_provider_daily_limit CHECK (daily_request_limit IS NULL OR daily_request_limit > 0),
    CONSTRAINT chk_assistant_provider_base_url CHECK (base_url LIKE 'https://%')
);

-- Un slug par fournisseur vivant ; un fournisseur supprimé libère son slug.
CREATE UNIQUE INDEX uq_assistant_llm_providers_slug
    ON assistant_llm_providers (slug) WHERE deleted_at IS NULL;
