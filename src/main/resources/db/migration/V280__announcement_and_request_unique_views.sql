-- Personnes qui ont vu un trajet ou une demande de colis, une ligne par personne.
--
-- Les trajets n'avaient aucun compteur côté app : seule la page web publique de
-- l'affiche était comptée (announcements.share_view_count, V213). Les demandes
-- comptaient des ouvertures (package_requests.view_count, V261) : la même
-- personne qui rouvre cinq fois compte cinq. view_count reste alimenté pour les
-- versions de l'app qui l'affichent encore.
--
-- La contrainte unique tranche entre deux ouvertures simultanées de la même
-- personne : le second insert échoue et le service l'ignore.

CREATE TABLE announcement_views (
    id               UUID PRIMARY KEY,
    announcement_id  UUID NOT NULL REFERENCES announcements(id),
    viewer_id        UUID NOT NULL REFERENCES users(id),
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP,
    deleted_at       TIMESTAMP,
    CONSTRAINT uq_announcement_views UNIQUE (announcement_id, viewer_id)
);

CREATE TABLE package_request_views (
    id                  UUID PRIMARY KEY,
    package_request_id  UUID NOT NULL REFERENCES package_requests(id),
    viewer_id           UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP,
    deleted_at          TIMESTAMP,
    CONSTRAINT uq_package_request_views UNIQUE (package_request_id, viewer_id)
);
