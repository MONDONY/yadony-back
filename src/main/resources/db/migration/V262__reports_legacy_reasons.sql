-- V262 : rattrapage des signalements créés avant la PR #206.
--
-- Jusqu'au 2026-08-19, reports.reason recevait un texte libre saisi dans l'app
-- (« Problème de paiement », « Problème avec un colis », « Problème avec un
-- utilisateur », « Bug de l'application », « Autre »). Depuis, l'entité mappe la
-- colonne sur l'enum ReportReason sans que les lignes existantes aient été
-- converties : Hibernate lève « No enum constant ReportReason.… » et toute liste
-- admin contenant une de ces lignes tombe en 500.
--
-- 1) Le libellé d'origine est conservé en tête de la description pour les motifs
--    sans équivalent catalogué (le modérateur garde l'intention du signalant).
-- 2) Les libellés qui ont un équivalent exact passent sur leur code.
-- 3) Tout le reste devient OTHER.
-- 4) Un CHECK interdit qu'une valeur hors catalogue revienne.

UPDATE reports
SET description = '[Motif d''origine : ' || reason || ']'
        || CASE WHEN description IS NULL OR btrim(description) = '' THEN '' ELSE ' ' || description END
WHERE reason NOT IN ('HARASSMENT', 'FAKE_PROFILE', 'SCAM_ATTEMPT', 'PROHIBITED_ITEM', 'FALSE_INFORMATION',
                     'INAPPROPRIATE_CONTENT', 'SPAM', 'PAYMENT_ISSUE', 'APP_BUG', 'OTHER',
                     'Problème de paiement', 'Bug de l''application', 'Autre');

UPDATE reports
SET reason = CASE reason
                 WHEN 'Problème de paiement' THEN 'PAYMENT_ISSUE'
                 WHEN 'Bug de l''application' THEN 'APP_BUG'
                 ELSE 'OTHER'
             END
WHERE reason NOT IN ('HARASSMENT', 'FAKE_PROFILE', 'SCAM_ATTEMPT', 'PROHIBITED_ITEM', 'FALSE_INFORMATION',
                     'INAPPROPRIATE_CONTENT', 'SPAM', 'PAYMENT_ISSUE', 'APP_BUG', 'OTHER');

ALTER TABLE reports
    ADD CONSTRAINT chk_reports_reason CHECK (reason IN (
        'HARASSMENT', 'FAKE_PROFILE', 'SCAM_ATTEMPT', 'PROHIBITED_ITEM', 'FALSE_INFORMATION',
        'INAPPROPRIATE_CONTENT', 'SPAM', 'PAYMENT_ISSUE', 'APP_BUG', 'OTHER'));
