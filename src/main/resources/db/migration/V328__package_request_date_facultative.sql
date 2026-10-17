-- V328 : la date de départ d'une demande de colis devient facultative.
-- Sans date = « Date flexible » : la demande est compatible avec toutes les dates
-- du corridor, n'est jamais « urgente », et expire seule 60 jours après sa publication
-- (disclaimer_signed_at, repli sur created_at).
-- La tolérance n'a de sens qu'avec une date : elle devient nullable elle aussi
-- (le CHECK chk_pkg_req_tolerance BETWEEN 0 AND 7 laisse déjà passer NULL).
ALTER TABLE package_requests ALTER COLUMN desired_date DROP NOT NULL;
ALTER TABLE package_requests ALTER COLUMN date_tolerance_days DROP NOT NULL;

