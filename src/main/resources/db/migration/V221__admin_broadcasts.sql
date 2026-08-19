-- Lot D — historique des broadcasts de notifications envoyes par un administrateur.
--
-- Table d'AUDIT FONCTIONNEL, distincte d'audit_log : elle porte le corps complet du
-- message et le compteur de destinataires, que la page « Communications » relit. Les
-- colonnes de ciblage sont a plat plutot qu'en jsonb : quatre colonnes suffisent, et
-- un filtre SQL sur target_type reste lisible.
CREATE TABLE admin_broadcasts (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(120)  NOT NULL,
    body                VARCHAR(500)  NOT NULL,
    -- ALL | SENDERS | TRAVELERS | CORRIDOR | USER (BroadcastTargetType)
    target_type         VARCHAR(20)   NOT NULL,
    -- Ville de depart / d'arrivee, renseignees uniquement pour target_type = 'CORRIDOR'.
    -- Il n'existe AUCUNE notion de corridor sur users : le ciblage passe par les
    -- annonces et les bids de l'utilisateur, apparies sur la ville.
    target_origin       VARCHAR(100),
    target_destination  VARCHAR(100),
    -- Renseigne uniquement pour target_type = 'USER'.
    target_user_id      UUID,
    recipient_count     INTEGER       NOT NULL DEFAULT 0,
    admin_id            UUID          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_admin_broadcasts_created_at ON admin_broadcasts (created_at DESC);

COMMENT ON TABLE admin_broadcasts IS
    'Historique des envois de notifications en masse declenches depuis le back-office (Lot D).';
COMMENT ON COLUMN admin_broadcasts.recipient_count IS
    'Nombre de destinataires resolus au moment de l''envoi — fige, jamais recalcule.';
