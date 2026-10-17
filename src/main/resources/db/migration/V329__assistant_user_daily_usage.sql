-- Questions posées à l'assistant IA par utilisateur et par jour (UTC). Gardées en base pour que le quota
-- du jour (platform_settings assistant.daily_quota) survive aux redémarrages du back : il protège le coût
-- des fournisseurs payants. Avant, un cache en mémoire repartait à zéro à chaque déploiement.
--
-- Clé : l'uid Firebase, ce que reçoit le quota. Pas de clé étrangère vers users : un compte connecté mais
-- pas encore inscrit (aucune ligne users) a accès à l'assistant, et la ligne users d'un compte supprimé
-- reste en place (soft delete, pseudonymisée), si bien qu'aucun lien n'est à défaire.
-- Table technique de compteurs : pas de soft delete, les lignes de plus de 30 jours sont purgées chaque
-- nuit (AssistantUserDailyUsageCleanupScheduler).

CREATE TABLE assistant_user_daily_usage (
    firebase_uid VARCHAR(128) NOT NULL,
    usage_date   DATE         NOT NULL,
    questions    INTEGER      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_assistant_user_daily_usage PRIMARY KEY (firebase_uid, usage_date),
    CONSTRAINT chk_assistant_user_daily_usage_questions CHECK (questions >= 0)
);

-- Purge nocturne des jours anciens.
CREATE INDEX idx_assistant_user_daily_usage_date
    ON assistant_user_daily_usage (usage_date);
