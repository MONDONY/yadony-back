-- GP Pro : badge attribué par l'admin, qui remplace Kilo Pro (décision du propriétaire, 17/10/2026).
-- Aucun backfill : les titulaires de kilo_pro restent gp_pro = FALSE, les colonnes kilo_pro ne sont pas touchées.
-- Soft delete (deleted_at) partout, jamais de DELETE physique.

ALTER TABLE users
    ADD COLUMN gp_pro                BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN gp_pro_granted_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN gp_pro_revoked_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN gp_pro_revoked_reason VARCHAR(1000);

CREATE INDEX idx_users_gp_pro ON users (gp_pro) WHERE gp_pro = TRUE;

ALTER TABLE announcements ADD COLUMN traveler_is_gp_pro BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE gp_pro_applications (
    id                UUID                     DEFAULT gen_random_uuid() NOT NULL,
    user_id           UUID                     NOT NULL,
    status            VARCHAR(20)              NOT NULL,
    source            VARCHAR(20)              NOT NULL,
    travel_frequency  VARCHAR(20),
    commercial_name   VARCHAR(80),
    decided_by        UUID,
    decided_at        TIMESTAMP WITH TIME ZONE,
    decision_reason   VARCHAR(1000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT gp_pro_applications_pkey PRIMARY KEY (id),
    CONSTRAINT gp_pro_applications_user_fkey FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT gp_pro_applications_status_check
        CHECK (status IN ('AWAITING_KYC', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT gp_pro_applications_source_check
        CHECK (source IN ('SIGNUP', 'PROFILE', 'ADMIN')),
    CONSTRAINT gp_pro_applications_frequency_check
        CHECK (travel_frequency IS NULL
               OR travel_frequency IN ('WEEKLY', 'TWICE_A_MONTH', 'MONTHLY', 'LESS_OFTEN'))
);

-- Au plus une demande ouverte par personne.
CREATE UNIQUE INDEX uq_gp_pro_applications_open
    ON gp_pro_applications (user_id)
    WHERE status IN ('AWAITING_KYC', 'PENDING') AND deleted_at IS NULL;

CREATE INDEX idx_gp_pro_applications_status ON gp_pro_applications (status, created_at)
    WHERE deleted_at IS NULL;

CREATE TABLE gp_pro_documents (
    id              UUID                     DEFAULT gen_random_uuid() NOT NULL,
    application_id  UUID,
    owner_id        UUID                     NOT NULL,
    doc_type        VARCHAR(30)              NOT NULL,
    storage_key     VARCHAR(500),
    purged_at       TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT gp_pro_documents_pkey PRIMARY KEY (id),
    CONSTRAINT gp_pro_documents_application_fkey
        FOREIGN KEY (application_id) REFERENCES gp_pro_applications (id),
    CONSTRAINT gp_pro_documents_owner_fkey FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT gp_pro_documents_type_check
        CHECK (doc_type IN ('BUSINESS_REGISTRATION', 'TRAVEL_PROOF', 'SOCIAL_PROFILE', 'OTHER'))
);

CREATE INDEX idx_gp_pro_documents_application ON gp_pro_documents (application_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_gp_pro_documents_orphans ON gp_pro_documents (created_at)
    WHERE application_id IS NULL AND purged_at IS NULL AND deleted_at IS NULL;

CREATE TABLE gp_pro_review_flags (
    id            UUID                     DEFAULT gen_random_uuid() NOT NULL,
    user_id       UUID                     NOT NULL,
    reason        VARCHAR(30)              NOT NULL,
    detected_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMP WITH TIME ZONE,
    dismissed_at  TIMESTAMP WITH TIME ZONE,
    dismissed_by  UUID,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT gp_pro_review_flags_pkey PRIMARY KEY (id),
    CONSTRAINT gp_pro_review_flags_user_fkey FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT gp_pro_review_flags_reason_check
        CHECK (reason IN ('LOW_RATING', 'OPEN_DISPUTE', 'OPEN_REPORT', 'INACTIVE', 'KYC_NOT_VERIFIED'))
);

-- Un seul signalement actif par personne et par motif.
CREATE UNIQUE INDEX uq_gp_pro_review_flags_active
    ON gp_pro_review_flags (user_id, reason)
    WHERE resolved_at IS NULL;

COMMENT ON TABLE gp_pro_applications IS 'Demandes du badge GP Pro, décidées par l''admin.';
COMMENT ON TABLE gp_pro_documents IS 'Justificatifs d''une demande GP Pro ; storage_key vidé à la purge.';
COMMENT ON TABLE gp_pro_review_flags IS 'Titulaires GP Pro à réexaminer (note basse, litige, inactivité...).';
