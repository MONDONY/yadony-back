-- Cloche du panel admin : fil des nouveautés et compteurs du menu.
--
-- admin_users.notifications_seen_at : dernière consultation de la cloche, par administrateur.
-- NULL = jamais consultée (les 7 derniers jours comptent alors comme non lus). Seul
-- POST /admin/notifications/mark-seen l'écrit, et jamais en arrière.
--
-- Index : chaque source du fil est lue par date décroissante sur 30 jours au plus, au plus une
-- fois toutes les 15 secondes pour tout le panel (cache Caffeine partagé). Index partiels quand
-- la source ne porte que sur une fraction de la table.

ALTER TABLE admin_users ADD COLUMN notifications_seen_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_notif_reports_created
    ON reports (created_at DESC) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notif_support_tickets_created
    ON support_tickets (created_at DESC) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notif_support_messages_user
    ON support_messages (created_at DESC) WHERE author_type = 'USER' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notif_disputes_created
    ON disputes (created_at DESC) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notif_cancellations_noshow
    ON cancellations (created_at DESC) WHERE no_show_status = 'PENDING_CONFIRMATION';

CREATE INDEX IF NOT EXISTS idx_notif_kyc_submitted
    ON kyc_schema.kyc_verifications (submitted_at DESC) WHERE submitted_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notif_wallet_refunds_manual
    ON wallet_refund_requests (requested_at DESC) WHERE channel = 'MANUAL_ADMIN';

CREATE INDEX IF NOT EXISTS idx_notif_users_deletion
    ON users (deletion_requested_at DESC) WHERE deletion_requested_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notif_admin_alerts_open
    ON admin_alerts (created_at DESC) WHERE resolved = FALSE;
