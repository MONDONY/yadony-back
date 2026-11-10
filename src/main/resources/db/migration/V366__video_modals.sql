-- Modals vidéo (vidéo de présentation affichée dans l'app, réglée depuis l'admin).
--
-- Une campagne = un modal : titre, sous-titre, vidéo YouTube, 0 à 3 points forts, deux
-- boutons, des déclencheurs (où le modal s'ouvre) et une version courante. Publier une
-- nouvelle version (nouvelle vidéo) remontre le modal au public choisi pour cette version.
-- Tant qu'aucune campagne n'est active, l'app n'affiche rien.
CREATE TABLE video_modal_campaigns (
    id                    UUID                     DEFAULT gen_random_uuid() NOT NULL,
    name                  VARCHAR(120)             NOT NULL,
    youtube_url           VARCHAR(500)             NOT NULL,
    youtube_video_id      VARCHAR(11)              NOT NULL,
    duration_seconds      INTEGER,
    title_fr              VARCHAR(120)             NOT NULL,
    title_en              VARCHAR(120),
    subtitle_fr           VARCHAR(300),
    subtitle_en           VARCHAR(300),
    primary_label_fr      VARCHAR(60),
    primary_label_en      VARCHAR(60),
    secondary_label_fr    VARCHAR(60),
    secondary_label_en    VARCHAR(60),
    -- Listes JSON écrites par l'API (convertisseurs JPA), jamais interrogées en SQL : TEXT
    -- et non jsonb, pour que les tests H2 les relisent comme Postgres.
    -- [{"icon":"PARCEL","textFr":"…","textEn":"…"}], au plus 3 (contrôlé par l'API).
    highlights            TEXT                     NOT NULL DEFAULT '[]',
    -- ["APP_OPEN","SEARCH_TAB",…], au moins un (contrôlé par l'API).
    triggers              TEXT                     NOT NULL DEFAULT '[]',
    max_displays          INTEGER                  NOT NULL DEFAULT 3,
    starts_at             TIMESTAMP WITH TIME ZONE,
    ends_at               TIMESTAMP WITH TIME ZONE,
    active                BOOLEAN                  NOT NULL DEFAULT FALSE,
    version               INTEGER                  NOT NULL DEFAULT 1,
    audience              VARCHAR(30)              NOT NULL DEFAULT 'NEVER_ANSWERED',
    version_published_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT video_modal_campaigns_pkey PRIMARY KEY (id),
    CONSTRAINT video_modal_campaigns_video_id_check CHECK (youtube_video_id ~ '^[A-Za-z0-9_-]{11}$'),
    CONSTRAINT video_modal_campaigns_duration_check CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    CONSTRAINT video_modal_campaigns_max_displays_check CHECK (max_displays BETWEEN 1 AND 10),
    CONSTRAINT video_modal_campaigns_version_check CHECK (version >= 1),
    CONSTRAINT video_modal_campaigns_audience_check
        CHECK (audience IN ('NEVER_ANSWERED', 'EVERYONE', 'NOT_WATCHED_PREVIOUS')),
    CONSTRAINT video_modal_campaigns_period_check
        CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at)
);

-- Lecture de l'app à chaque ouverture : campagnes actives seulement.
CREATE INDEX idx_video_modal_campaigns_active
    ON video_modal_campaigns (version_published_at DESC)
    WHERE active = TRUE AND deleted_at IS NULL;

-- Réponse d'un utilisateur à une version d'une campagne. Une ligne par campagne, utilisateur
-- et version : le compteur d'affichages et le statut (jamais rétrogradé :
-- COMPLETED > OPENED > DISMISSED > SHOWN) y sont tenus.
CREATE TABLE video_modal_responses (
    id              UUID                     DEFAULT gen_random_uuid() NOT NULL,
    campaign_id     UUID                     NOT NULL,
    user_id         UUID                     NOT NULL,
    version         INTEGER                  NOT NULL,
    displays        INTEGER                  NOT NULL DEFAULT 0,
    status          VARCHAR(20)              NOT NULL DEFAULT 'SHOWN',
    last_shown_at   TIMESTAMP WITH TIME ZONE,
    answered_at     TIMESTAMP WITH TIME ZONE,
    opened_at       TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT video_modal_responses_pkey PRIMARY KEY (id),
    CONSTRAINT video_modal_responses_campaign_fkey
        FOREIGN KEY (campaign_id) REFERENCES video_modal_campaigns (id),
    CONSTRAINT video_modal_responses_user_fkey FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_video_modal_responses_campaign_user_version UNIQUE (campaign_id, user_id, version),
    CONSTRAINT video_modal_responses_version_check CHECK (version >= 1),
    CONSTRAINT video_modal_responses_displays_check CHECK (displays >= 0),
    CONSTRAINT video_modal_responses_status_check
        CHECK (status IN ('SHOWN', 'DISMISSED', 'OPENED', 'COMPLETED'))
);

-- Éligibilité : toutes les réponses d'un utilisateur.
CREATE INDEX idx_video_modal_responses_user ON video_modal_responses (user_id);
