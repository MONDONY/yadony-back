-- Cloche du panel admin : état « lu » par notification et par administrateur.
--
-- Jusqu'ici seule admin_users.notifications_seen_at (V271) existait : ouvrir la cloche marquait
-- tout comme lu d'un coup. Cette table garde les notifications lues une à une ; la date de V271
-- reste le seuil de « Tout marquer comme lu ». Une notification est non lue quand elle est plus
-- récente que ce seuil ET qu'elle n'a pas de ligne ici.
--
-- notification_id : identifiant du fil, « <TYPE>:<uuid> » (ex. REPORT_CREATED:...), stable d'un
-- appel à l'autre. Pas de clé étrangère : il désigne des sources variées, et la suppression d'un
-- compte admin ne doit pas dépendre de cet historique.
-- Écriture idempotente (une ligne par couple) ; jamais de suppression physique.

CREATE TABLE admin_notification_reads (
    id UUID PRIMARY KEY,
    admin_id UUID NOT NULL,
    notification_id VARCHAR(80) NOT NULL,
    read_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP,
    CONSTRAINT uq_admin_notification_reads UNIQUE (admin_id, notification_id)
);

-- Relu à chaque calcul du non-lu : les lectures récentes d'un administrateur.
CREATE INDEX idx_admin_notification_reads_recent ON admin_notification_reads (admin_id, read_at DESC);
