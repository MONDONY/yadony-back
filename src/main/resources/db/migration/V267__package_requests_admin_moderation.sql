-- V267 : modération admin des demandes d'envoi et de leurs signalements.
--
-- 1) Nouveau statut REMOVED_BY_ADMIN. Le CHECK posé par V185 énumère les statuts :
--    il est recréé à l'identique, plus la nouvelle valeur.
-- 2) status_before_removal : statut d'avant le retrait, restitué par la restauration
--    (même rôle que la colonne de V220 sur announcements). NULL hors retrait.
-- 3) Les signalements de demande (package_request_reports, motif libre) rejoignent
--    la boîte générique reports (cible PACKAGE_REQUEST). Aucun CHECK n'existe sur
--    reports.target_type (V164) ; le CHECK chk_reports_reason (V263) n'est pas
--    touché : le motif est converti vers le catalogue. Table de conversion, miroir
--    exact de PackageRequestReportReasons côté Java :
--      PROHIBITED    -> PROHIBITED_ITEM
--      SCAM          -> SCAM_ATTEMPT
--      INAPPROPRIATE -> INAPPROPRIATE_CONTENT
--      OTHER         -> OTHER
--      codes du catalogue applicables (PROHIBITED_ITEM, SCAM_ATTEMPT,
--      FALSE_INFORMATION, INAPPROPRIATE_CONTENT) -> eux-mêmes
--      tout le reste -> OTHER, motif d'origine en tête de la description.
--    Sans doublon : une ligne déjà présente (même demande, même signalant) n'est pas
--    recopiée. created_at conservé (stocké en UTC sans fuseau par l'entité).

ALTER TABLE package_requests
  DROP CONSTRAINT IF EXISTS chk_pkg_req_status;

ALTER TABLE package_requests
  ADD CONSTRAINT chk_pkg_req_status CHECK (
    status IN ('DRAFT', 'OPEN', 'NEGOTIATING', 'ACCEPTED',
               'EXPIRED', 'CANCELLED', 'COMPLETED', 'REMOVED_BY_ADMIN')
  );

ALTER TABLE package_requests
  ADD COLUMN IF NOT EXISTS status_before_removal VARCHAR(20);

ALTER TABLE package_requests
  ADD CONSTRAINT chk_pkg_req_status_before_removal CHECK (
    status_before_removal IS NULL
    OR status_before_removal IN ('DRAFT', 'OPEN', 'NEGOTIATING', 'ACCEPTED',
                                 'EXPIRED', 'CANCELLED', 'COMPLETED')
  );

INSERT INTO reports (id, target_type, target_id, reporter_id, reason, description, status,
                     created_at, updated_at)
SELECT gen_random_uuid(),
       'PACKAGE_REQUEST',
       prr.package_request_id,
       prr.reporter_id,
       CASE upper(btrim(prr.reason))
           WHEN 'PROHIBITED'            THEN 'PROHIBITED_ITEM'
           WHEN 'SCAM'                  THEN 'SCAM_ATTEMPT'
           WHEN 'INAPPROPRIATE'         THEN 'INAPPROPRIATE_CONTENT'
           WHEN 'OTHER'                 THEN 'OTHER'
           WHEN 'PROHIBITED_ITEM'       THEN 'PROHIBITED_ITEM'
           WHEN 'SCAM_ATTEMPT'          THEN 'SCAM_ATTEMPT'
           WHEN 'FALSE_INFORMATION'     THEN 'FALSE_INFORMATION'
           WHEN 'INAPPROPRIATE_CONTENT' THEN 'INAPPROPRIATE_CONTENT'
           ELSE 'OTHER'
       END,
       CASE
           WHEN upper(btrim(prr.reason)) IN ('PROHIBITED', 'SCAM', 'INAPPROPRIATE', 'OTHER',
                                            'PROHIBITED_ITEM', 'SCAM_ATTEMPT',
                                            'FALSE_INFORMATION', 'INAPPROPRIATE_CONTENT')
               THEN prr.details
           ELSE '[Motif d''origine : ' || prr.reason || ']'
                || CASE WHEN prr.details IS NULL OR btrim(prr.details) = '' THEN ''
                        ELSE ' ' || prr.details END
       END,
       'OPEN',
       prr.created_at AT TIME ZONE 'UTC',
       prr.created_at AT TIME ZONE 'UTC'
FROM package_request_reports prr
WHERE NOT EXISTS (
    SELECT 1 FROM reports r
    WHERE r.target_type = 'PACKAGE_REQUEST'
      AND r.target_id = prr.package_request_id
      AND r.reporter_id = prr.reporter_id
);

CREATE INDEX IF NOT EXISTS idx_reports_target ON reports (target_type, target_id);
