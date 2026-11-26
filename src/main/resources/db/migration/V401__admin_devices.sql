-- Appareils (PWA admin mobile) qui reçoivent les notifications push des administrateurs.
CREATE TABLE admin_devices (
    id UUID PRIMARY KEY,
    -- Pas de clé étrangère vers admin_users : même choix que conversation_admin_notes (V353).
    admin_id UUID NOT NULL,
    fcm_token VARCHAR(512) NOT NULL,
    device_name VARCHAR(255),
    platform VARCHAR(10),
    -- Date du dernier push envoyé à l'appareil (sert de base à la déduplication / limitation).
    last_pushed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP,
    CONSTRAINT uq_admin_devices_fcm_token UNIQUE (fcm_token)
);

CREATE INDEX idx_admin_devices_admin ON admin_devices (admin_id) WHERE deleted_at IS NULL;
