-- Photos R2 : registre des envois (quotas) et journal des passages de purge.
-- Spec : docs-claude/docs/superpowers/specs/2026-10-10-photos-limites-purge-design.md

CREATE TABLE media_uploads (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    object_key  VARCHAR(1024) NOT NULL,
    owner_id    UUID         NOT NULL,
    -- PACKAGE_REQUEST | BID | TRACKING | AVATAR | REPORT | SUPPORT | MESSAGING (MediaPurpose)
    purpose     VARCHAR(32)  NOT NULL,
    bid_id      UUID,
    size_bytes  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_media_uploads_key UNIQUE (object_key)
);
CREATE INDEX idx_media_uploads_owner_created ON media_uploads (owner_id, created_at);
CREATE INDEX idx_media_uploads_bid_purpose ON media_uploads (bid_id, purpose) WHERE bid_id IS NOT NULL;
COMMENT ON TABLE media_uploads IS
    'Un envoi de photo vers R2 : sert aux quotas (par utilisateur sur 24 h, par bid pour le tracking). Jamais purgé.';

CREATE TABLE photo_purge_runs (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- PURGE (après livraison, horaire) | RECONCILE (R2 <-> base, quotidien)
    kind         VARCHAR(16) NOT NULL,
    dry_run      BOOLEAN     NOT NULL,
    trigger_source VARCHAR(16) NOT NULL,  -- SCHEDULE | ADMIN
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ,
    eligible     INT         NOT NULL DEFAULT 0,
    deleted      INT         NOT NULL DEFAULT 0,
    failed       INT         NOT NULL DEFAULT 0,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMPTZ
);
CREATE INDEX idx_photo_purge_runs_kind_started ON photo_purge_runs (kind, started_at DESC);

ALTER TABLE tracking_events ADD COLUMN photo_purged_at TIMESTAMPTZ;
ALTER TABLE package_request_photos ADD COLUMN purged_at TIMESTAMPTZ;
