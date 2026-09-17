-- Refonte « Ma demande » (2026-09) : l'expéditeur voit combien de voyageurs ont
-- consulté sa demande et peut inviter un voyageur dont le trajet correspond.
--
-- view_count volontairement NULLABLE avec DEFAULT : une colonne NOT NULL casse les
-- tests sur H2, dont le DDL vient de JPA sans le DEFAULT de Flyway (même choix que
-- announcements.share_view_count, V213). Toutes les lectures passent par COALESCE.
ALTER TABLE package_requests
    ADD COLUMN IF NOT EXISTS view_count BIGINT DEFAULT 0;

COMMENT ON COLUMN package_requests.view_count IS
    'Consultations du détail par un utilisateur connecté autre que l''expéditeur, demande OPEN ou NEGOTIATING.';

-- Une invitation par couple demande/trajet : un second geste sur le même voyageur
-- ne renvoie pas de push.
CREATE TABLE package_request_invitations (
    id                 UUID PRIMARY KEY,
    package_request_id UUID      NOT NULL REFERENCES package_requests (id),
    announcement_id    UUID      NOT NULL REFERENCES announcements (id),
    traveler_id        UUID      NOT NULL REFERENCES users (id),
    sender_id          UUID      NOT NULL REFERENCES users (id),
    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL,
    deleted_at         TIMESTAMP NULL,
    -- L'index de la contrainte unique commence déjà par package_request_id : il
    -- couvre les lookups sur cette seule colonne, pas besoin d'un index dédié.
    CONSTRAINT uq_package_request_invitation UNIQUE (package_request_id, announcement_id)
);
