-- Diffusion, lot 1 : moteur de ciblage. Segments reutilisables et regle d'audience
-- copiee sur chaque envoi (l'historique garde la regle telle qu'envoyee).
CREATE TABLE broadcast_segments (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(80)   NOT NULL,
    description VARCHAR(300),
    rule        JSONB         NOT NULL,
    created_by  UUID          NOT NULL,
    updated_by  UUID          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

-- Nom unique parmi les segments non supprimes, sans tenir compte de la casse.
CREATE UNIQUE INDEX uq_broadcast_segments_name
    ON broadcast_segments (lower(name))
    WHERE deleted_at IS NULL;

-- target_type n'a aucune contrainte CHECK (V221) : la valeur RULE est acceptee telle quelle.
ALTER TABLE admin_broadcasts
    ADD COLUMN audience_rule JSONB,
    ADD COLUMN segment_id    UUID REFERENCES broadcast_segments (id),
    ADD COLUMN segment_name  VARCHAR(80);

COMMENT ON COLUMN admin_broadcasts.audience_rule IS
    'Regle d''audience copiee au moment de l''envoi (target_type = RULE ou segment).';
COMMENT ON COLUMN admin_broadcasts.segment_name IS
    'Nom du segment au moment de l''envoi, copie pour survivre a un renommage ou une suppression.';
