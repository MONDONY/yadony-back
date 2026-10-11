-- FLUTTER-JH (décision du 11/10/2026) : les trajets complets restent visibles en recherche,
-- avec « Me prévenir si de la place se libère ». Un abonnement par personne et par trajet,
-- consommé (notified_at) à la première place libérée : une seule notification, jamais deux.
-- Soft delete (deleted_at) pour le désabonnement, jamais de DELETE physique.

CREATE TABLE trip_space_alerts (
    id               UUID                     DEFAULT gen_random_uuid() NOT NULL,
    announcement_id  UUID                     NOT NULL,
    subscriber_id    UUID                     NOT NULL,
    notified_at      TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT trip_space_alerts_pkey PRIMARY KEY (id),
    CONSTRAINT trip_space_alerts_announcement_fkey
        FOREIGN KEY (announcement_id) REFERENCES announcements (id),
    CONSTRAINT trip_space_alerts_subscriber_fkey
        FOREIGN KEY (subscriber_id) REFERENCES users (id)
);

-- Au plus un abonnement en attente par personne et par trajet (l'abonnement est idempotent).
CREATE UNIQUE INDEX uq_trip_space_alerts_pending
    ON trip_space_alerts (announcement_id, subscriber_id)
    WHERE notified_at IS NULL AND deleted_at IS NULL;

-- Lecture des abonnés en attente d'un trajet au moment où la place se libère.
CREATE INDEX idx_trip_space_alerts_subscriber_pending
    ON trip_space_alerts (subscriber_id)
    WHERE notified_at IS NULL AND deleted_at IS NULL;

COMMENT ON TABLE trip_space_alerts IS
    'Abonnement « Me prévenir si de la place se libère » sur un trajet FULL. Consommé (notified_at) à la première notification.';
