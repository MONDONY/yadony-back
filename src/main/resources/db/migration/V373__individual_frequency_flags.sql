-- V373 : alerte sur les « faux particuliers » (lot 4).
--
-- Un voyageur déclaré particulier (users.traveler_status = 'INDIVIDUAL', sans badge GP Pro) qui a eu, sur 30 jours
-- glissants, plus de trajets distincts avec une réservation acceptée que le seuil réglé dans l'admin
-- (platform_settings.individual_trips_alert_threshold), ou qui a demandé le badge GP Pro avec la fréquence « chaque
-- semaine » sans l'obtenir, est signalé à l'équipe et reçoit une question neutre « Confirmez votre statut ».
-- Jamais de blocage, de sanction ni de changement de statut : le contrôle n'écrit que dans cette table.
--
-- Valeurs figées au moment de la détection (trips_count, window_days, threshold, weekly_application) : l'admin voit
-- pourquoi la personne a été signalée, même si le seuil change ensuite.
CREATE TABLE individual_frequency_flags (
    id                 UUID                     DEFAULT gen_random_uuid() NOT NULL,
    user_id            UUID                     NOT NULL,
    trips_count        INTEGER                  NOT NULL,
    window_days        INTEGER                  NOT NULL,
    threshold          INTEGER                  NOT NULL,
    -- Vrai si une demande GP Pro « chaque semaine », refusée ou annulée, a été faite dans la fenêtre.
    weekly_application BOOLEAN                  NOT NULL DEFAULT FALSE,
    detected_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- La condition a cessé (compte revenu sous le seuil, badge GP Pro ou statut professionnel).
    resolved_at        TIMESTAMP WITH TIME ZONE,
    dismissed_at       TIMESTAMP WITH TIME ZONE,
    dismissed_by       UUID,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT individual_frequency_flags_pkey PRIMARY KEY (id),
    CONSTRAINT individual_frequency_flags_user_fkey FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT individual_frequency_flags_trips_count_check CHECK (trips_count >= 0),
    CONSTRAINT individual_frequency_flags_window_days_check CHECK (window_days > 0),
    CONSTRAINT individual_frequency_flags_threshold_check CHECK (threshold BETWEEN 1 AND 100)
);

-- Un seul drapeau non résolu par personne. Un drapeau écarté mais non résolu bloque la recréation : il ne revient
-- qu'après que la condition a cessé puis repris.
CREATE UNIQUE INDEX uq_individual_frequency_flags_open
    ON individual_frequency_flags (user_id)
    WHERE resolved_at IS NULL;

COMMENT ON TABLE individual_frequency_flags IS
    'Particuliers qui voyagent comme des professionnels, à confirmer (lot 4). Jamais de blocage automatique.';
