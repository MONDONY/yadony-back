-- Gel des versements d'un voyageur banni ou dont la vérification d'identité a été retirée.
--
-- payout_holds : un enregistrement par motif (BANNED, KYC_REVOKED). Jamais supprimé : la levée
-- pose released_at. Au plus un gel actif par (utilisateur, motif), garanti par l'index unique
-- partiel. Les deux motifs peuvent coexister : le voyageur reste gelé tant qu'un gel actif
-- subsiste. Table dédiée plutôt que deux colonnes sur users : deux motifs simultanés, un
-- historique des gels, et aucune écriture sur la ligne users (verrou optimiste, V49) depuis le
-- package payments.
--
-- payments.payout_held_at : versement retenu à la livraison (bénéficiaire gelé). Le paiement
-- reste ESCROW ; seul un administrateur le libère ensuite (dérogation motivée) ou le rembourse.
--
-- kyc_schema.kyc_refused_sessions : sessions de vérification refusées ou révoquées par un
-- administrateur. Didit resert une session inachevée du même utilisateur : sans ce registre, la
-- session refusée pouvait revenir et une approbation tardive valider le compte.

CREATE TABLE payout_holds (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id),
    reason      VARCHAR(30) NOT NULL,
    held_since  TIMESTAMP   NOT NULL,
    held_by     UUID,
    released_at TIMESTAMP,
    released_by UUID,
    created_at  TIMESTAMP   NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    updated_at  TIMESTAMP   NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    deleted_at  TIMESTAMP,
    CONSTRAINT chk_payout_holds_reason CHECK (reason IN ('BANNED', 'KYC_REVOKED'))
);

CREATE UNIQUE INDEX uq_payout_holds_active
    ON payout_holds (user_id, reason)
    WHERE released_at IS NULL AND deleted_at IS NULL;

ALTER TABLE payments ADD COLUMN payout_held_at TIMESTAMP;

-- File admin GET /admin/payments?held=true et compteur queues.heldPayouts.
CREATE INDEX idx_payments_payout_held
    ON payments (payout_held_at)
    WHERE status = 'ESCROW' AND payout_held_at IS NOT NULL;

CREATE TABLE kyc_schema.kyc_refused_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL,
    provider            VARCHAR(20),
    session_id          VARCHAR(255) NOT NULL,
    decision_kind       VARCHAR(20)  NOT NULL,
    decided_by_admin_id UUID,
    decided_at          TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    updated_at          TIMESTAMP    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    deleted_at          TIMESTAMP,
    CONSTRAINT uq_kyc_refused_sessions_session UNIQUE (session_id),
    CONSTRAINT chk_kyc_refused_sessions_kind CHECK (decision_kind IN ('REJECTED', 'REVOKED'))
);

-- ── Rattrapage ───────────────────────────────────────────────────────────────────────────────

-- 1. Comptes bannis encore vivants. Un compte finalisé (suppression RGPD) est aussi BANNED mais
--    porte deleted_at : il a son propre traitement et n'est pas gelé ici.
INSERT INTO payout_holds (user_id, reason, held_since)
SELECT u.id, 'BANNED',
       COALESCE((SELECT MAX(a.created_at) AT TIME ZONE 'UTC'
                 FROM audit_log a
                 WHERE a.entity_type = 'USER' AND a.entity_id = u.id AND a.action = 'USER_BANNED_BY_ADMIN'),
                NOW() AT TIME ZONE 'UTC')
FROM users u
WHERE u.status = 'BANNED'
  AND u.deleted_at IS NULL;

-- 2. Vérifications révoquées et jamais revérifiées depuis. La décision quitte la ligne à la
--    nouvelle session : l'audit (KYC_REVOKED_BY_ADMIN, sur l'identifiant de la ligne KYC) garde
--    la trace d'une révocation suivie d'une nouvelle tentative encore en cours.
WITH revoked_line AS (
    SELECT k.user_id, COALESCE(k.decided_at, NOW() AT TIME ZONE 'UTC') AS since
    FROM kyc_schema.kyc_verifications k
    WHERE k.decision_kind = 'REVOKED' AND k.deleted_at IS NULL
),
last_revoke AS (
    SELECT k.user_id, MAX(a.created_at) AS at
    FROM audit_log a
    JOIN kyc_schema.kyc_verifications k ON k.id = a.entity_id
    WHERE a.entity_type = 'kyc_verification' AND a.action = 'KYC_REVOKED_BY_ADMIN'
    GROUP BY k.user_id
),
last_verify AS (
    SELECT k.user_id, MAX(a.created_at) AS at
    FROM audit_log a
    JOIN kyc_schema.kyc_verifications k ON k.id = a.entity_id
    WHERE a.entity_type = 'kyc_verification' AND a.action IN ('KYC_VERIFIED', 'KYC_VERIFIED_BY_ADMIN')
    GROUP BY k.user_id
),
revoked_history AS (
    SELECT r.user_id, r.at AT TIME ZONE 'UTC' AS since
    FROM last_revoke r
    LEFT JOIN last_verify v ON v.user_id = r.user_id
    WHERE v.at IS NULL OR v.at < r.at
),
candidates AS (
    SELECT user_id, MIN(since) AS since
    FROM (SELECT * FROM revoked_line UNION ALL SELECT * FROM revoked_history) c
    GROUP BY user_id
)
INSERT INTO payout_holds (user_id, reason, held_since)
SELECT c.user_id, 'KYC_REVOKED', c.since
FROM candidates c
JOIN users u ON u.id = c.user_id
WHERE u.deleted_at IS NULL
  AND u.kyc_status <> 'VERIFIED';

-- 3. Sessions déjà refusées ou révoquées : celle encore portée par la ligne, et celles de
--    l'historique d'audit (payload.sessionId des décisions admin).
INSERT INTO kyc_schema.kyc_refused_sessions (user_id, provider, session_id, decision_kind, decided_by_admin_id, decided_at)
SELECT k.user_id, k.provider, k.verification_session_id, k.decision_kind, k.decided_by_admin_id,
       COALESCE(k.decided_at, NOW() AT TIME ZONE 'UTC')
FROM kyc_schema.kyc_verifications k
WHERE k.decision_kind IN ('REJECTED', 'REVOKED')
  AND k.verification_session_id IS NOT NULL
  AND k.verification_session_id <> ''
ON CONFLICT (session_id) DO NOTHING;

INSERT INTO kyc_schema.kyc_refused_sessions (user_id, provider, session_id, decision_kind, decided_by_admin_id, decided_at)
SELECT DISTINCT ON (a.payload ->> 'sessionId')
       k.user_id, k.provider, a.payload ->> 'sessionId',
       CASE a.action WHEN 'KYC_REVOKED_BY_ADMIN' THEN 'REVOKED' ELSE 'REJECTED' END,
       a.actor_id, a.created_at AT TIME ZONE 'UTC'
FROM audit_log a
JOIN kyc_schema.kyc_verifications k ON k.id = a.entity_id
WHERE a.entity_type = 'kyc_verification'
  AND a.action IN ('KYC_REJECTED_BY_ADMIN', 'KYC_REVOKED_BY_ADMIN')
  AND COALESCE(a.payload ->> 'sessionId', '') <> ''
ORDER BY a.payload ->> 'sessionId', a.created_at
ON CONFLICT (session_id) DO NOTHING;
