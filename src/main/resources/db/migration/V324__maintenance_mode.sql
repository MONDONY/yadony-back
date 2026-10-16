-- Mode maintenance piloté depuis le back-office : une ligne unique d'état, et la liste des
-- comptes qui continuent d'utiliser l'application pendant la maintenance (propriétaire,
-- testeurs). Pendant la maintenance, l'API mobile répond 503 `maintenance`
-- (MaintenanceFilter) et l'application n'affiche plus que l'écran de maintenance.

CREATE TABLE maintenance_state (
    id               SMALLINT     PRIMARY KEY,
    enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    message          VARCHAR(300),
    estimated_end_at TIMESTAMP,
    started_at       TIMESTAMP,
    updated_by       UUID,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT chk_maintenance_state_singleton CHECK (id = 1)
);

INSERT INTO maintenance_state (id, enabled, updated_at) VALUES (1, FALSE, NOW());

CREATE TABLE maintenance_exemptions (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users(id),
    note       VARCHAR(120),
    created_by UUID         NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    deleted_at TIMESTAMP
);

-- Une seule exemption active par compte ; une exemption retirée (soft delete) peut être reposée.
CREATE UNIQUE INDEX uq_maintenance_exemptions_user_active
    ON maintenance_exemptions (user_id) WHERE deleted_at IS NULL;
