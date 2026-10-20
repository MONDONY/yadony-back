-- Effacement chez Didit des sessions de vérification d'identité d'un compte supprimé (RGPD).
--
-- La finalisation vide verification_session_id sur kyc_verifications : c'était la seule
-- référence à la session, et rien n'était effacé chez Didit (rétention illimitée par défaut).
-- Chaque session à effacer devient une ligne de cette file, écrite dans la transaction de la
-- finalisation : un arrêt du serveur ou une panne Didit n'en perd aucune. Un essai part après le
-- commit, le planificateur DiditSessionDeletionRetryJob rejoue les échecs avec un délai croissant,
-- puis abandonne (FAILED, alerte admin) après le dernier essai.
--
-- next_attempt_at sert aussi de bail : un essai le repousse avant d'appeler Didit, ce qui empêche
-- l'essai après commit et le planificateur de traiter la même ligne en même temps.

CREATE TABLE kyc_schema.didit_session_deletions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL,
    session_id       VARCHAR(255) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    last_attempt_at  TIMESTAMP,
    last_http_status INTEGER,
    completed_at     TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    updated_at       TIMESTAMP    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    deleted_at       TIMESTAMP,
    CONSTRAINT uq_didit_session_deletions_session UNIQUE (session_id),
    CONSTRAINT chk_didit_session_deletions_status CHECK (status IN ('PENDING', 'DONE', 'FAILED'))
);

CREATE INDEX idx_didit_session_deletions_due
    ON kyc_schema.didit_session_deletions (next_attempt_at)
    WHERE status = 'PENDING' AND deleted_at IS NULL;
