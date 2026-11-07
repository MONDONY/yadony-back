-- Suggestions de trajets selon les recherches (sans alerte posée par l'utilisateur).
--
-- Une ligne par utilisateur connecté et par corridor cherché, mise à jour à chaque nouvelle
-- recherche. Quand un trajet correspondant est publié et que la dernière recherche n'avait
-- rien trouvé, l'utilisateur est prévenu une fois (push + e-mail), puis la ligne s'éteint
-- jusqu'à sa prochaine recherche. Aucune coordonnée ici : l'e-mail est lu dans Firebase au
-- moment de l'envoi. Les lignes sont effacées 30 jours après la dernière recherche et à la
-- finalisation du compte (minimisation RGPD), d'où l'absence d'historique conservé.
CREATE TABLE search_interests (
    id                        UUID                     DEFAULT gen_random_uuid() NOT NULL,
    user_id                   UUID                     NOT NULL,
    departure_city            VARCHAR(100)             NOT NULL,
    arrival_city              VARCHAR(100)             NOT NULL,
    date_from                 DATE,
    date_to                   DATE,
    result_count              INTEGER                  NOT NULL DEFAULT 0,
    last_searched_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    notified_at               TIMESTAMP WITH TIME ZONE,
    notified_announcement_id  UUID,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at                TIMESTAMP WITH TIME ZONE,
    CONSTRAINT search_interests_pkey PRIMARY KEY (id),
    CONSTRAINT search_interests_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT search_interests_result_count_check CHECK (result_count >= 0)
);

-- Une seule ligne vivante par utilisateur et par corridor (villes insensibles à la casse).
CREATE UNIQUE INDEX uq_search_interests_user_corridor
    ON search_interests (user_id, lower(departure_city), lower(arrival_city))
    WHERE deleted_at IS NULL;

-- Recherche des intérêts à prévenir quand un trajet est publié sur un corridor.
CREATE INDEX idx_search_interests_pending_corridor
    ON search_interests (lower(departure_city), lower(arrival_city))
    WHERE notified_at IS NULL AND result_count = 0 AND deleted_at IS NULL;

-- Purge quotidienne et plafond hebdomadaire par utilisateur.
CREATE INDEX idx_search_interests_last_searched ON search_interests (last_searched_at);
CREATE INDEX idx_search_interests_user_notified ON search_interests (user_id, notified_at);

-- Interrupteur « Suggestions selon mes recherches » (activé par défaut). Le lien de
-- désinscription des e-mails le coupe.
ALTER TABLE user_notification_preferences
    ADD COLUMN search_suggestions BOOLEAN NOT NULL DEFAULT TRUE;
