-- Décisions d'administration sur une vérification d'identité (file de revue admin).
--
-- decided_by_admin_id / decided_at / decision_reason / decision_kind : la dernière décision
-- prise à la main par un administrateur sur la session courante. Remises à NULL par une
-- nouvelle session utilisateur et par le reset administrateur.
--
-- submitted_at : date à laquelle le fournisseur a placé la session en revue manuelle
-- (webhook Didit « In Review »). C'est ce qui distingue une ligne PENDING « parcours
-- terminé, en attente de décision » d'une ligne PENDING « parcours en cours » : users.kyc_status
-- passe déjà à PENDING dès la création de la session, il ne suffit donc pas.
--
-- decision_reason n'est jamais renvoyé à l'utilisateur : c'est le motif interne de l'admin.

ALTER TABLE kyc_schema.kyc_verifications
    ADD COLUMN decided_by_admin_id UUID,
    ADD COLUMN decided_at TIMESTAMP,
    ADD COLUMN decision_reason VARCHAR(1000),
    ADD COLUMN decision_kind VARCHAR(20),
    ADD COLUMN submitted_at TIMESTAMP;

ALTER TABLE kyc_schema.kyc_verifications
    ADD CONSTRAINT kyc_verifications_decision_kind_check
        CHECK (decision_kind IS NULL OR decision_kind IN ('APPROVED', 'REJECTED', 'REVOKED'));

-- Rattrapage : une ligne encore PENDING dont le dernier passage en revue n'a été suivi
-- d'aucune nouvelle session, d'aucun reset ni d'aucune fin de parcours est en revue.
UPDATE kyc_schema.kyc_verifications k
SET submitted_at = r.reviewed_at
FROM (SELECT entity_id, MAX(created_at) AS reviewed_at
      FROM audit_log
      WHERE entity_type = 'kyc_verification' AND action = 'KYC_IN_REVIEW'
      GROUP BY entity_id) r
WHERE r.entity_id = k.id
  AND k.status = 'PENDING'
  AND NOT EXISTS (SELECT 1 FROM audit_log a
                  WHERE a.entity_type = 'kyc_verification'
                    AND a.entity_id = k.id
                    AND a.action IN ('KYC_SESSION_CREATED', 'KYC_RESET_BY_ADMIN',
                                     'KYC_ABANDONED', 'KYC_EXPIRED', 'KYC_CANCELED')
                    AND a.created_at > r.reviewed_at);

-- File admin : filtre sur le statut, tri sur la date de passage en revue.
CREATE INDEX idx_kyc_verifications_queue
    ON kyc_schema.kyc_verifications (status, submitted_at)
    WHERE deleted_at IS NULL;
