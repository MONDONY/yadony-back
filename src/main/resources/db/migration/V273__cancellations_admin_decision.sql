-- V273 : décision d'un administrateur sur une déclaration de no-show.
--
-- L'écran Incidents > No-shows permet désormais de CONFIRMER ou de REJETER une
-- déclaration (POST /admin/cancellations/{id}/confirm|reject). no_show_status porte
-- l'effet (CONFIRMED, ou RESOLVED pour un rejet), ces colonnes portent la trace
-- lisible de la décision : sans elles, un rejet (RESOLVED) ne se distinguait pas
-- d'un litige lié résolu. L'audit_log reste la trace de référence, immuable.
--
-- Colonnes nullables : les lignes existantes n'ont jamais été tranchées par un admin.
-- Pas de FK vers admin_users : l'identifiant vient d'AdminPrincipal, comme
-- l'acteur de l'audit_log, et un compte admin supprimé ne doit pas bloquer la ligne.

ALTER TABLE cancellations
    ADD COLUMN admin_decision      VARCHAR(10),
    ADD COLUMN decided_by_admin_id UUID,
    ADD COLUMN decided_at          TIMESTAMPTZ,
    ADD COLUMN decision_reason     TEXT;

ALTER TABLE cancellations ADD CONSTRAINT cancellations_admin_decision_check
    CHECK (admin_decision IN ('CONFIRMED', 'REJECTED'));

-- Une décision n'existe jamais sans son auteur, sa date et son motif.
ALTER TABLE cancellations ADD CONSTRAINT cancellations_admin_decision_complete_check
    CHECK (admin_decision IS NULL
           OR (decided_by_admin_id IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL));

-- File admin : GET /admin/cancellations ne lit que les no-shows, filtrés par statut,
-- triés du plus récent au plus ancien.
CREATE INDEX IF NOT EXISTS idx_cancellations_noshow_admin_queue
    ON cancellations (no_show_status, created_at DESC)
    WHERE reason IN ('SENDER_NO_SHOW', 'RECIPIENT_NO_SHOW', 'TRAVELER_DELIVERY_NO_SHOW');
