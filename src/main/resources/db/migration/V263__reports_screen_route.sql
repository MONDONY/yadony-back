-- V263 : rapports de bug envoyés depuis le scarabée d'un écran de l'app.
--
-- 1) Nouveau motif SCREEN_BUG (cible APP) : le CHECK posé par V262 énumère le
--    catalogue, il faut l'étendre.
-- 2) screen_route : la route GoRouter de l'écran d'où part le rapport
--    (ex. /profile), pour que la modération sache où regarder.

ALTER TABLE reports DROP CONSTRAINT IF EXISTS chk_reports_reason;
ALTER TABLE reports
    ADD CONSTRAINT chk_reports_reason CHECK (reason IN (
        'HARASSMENT', 'FAKE_PROFILE', 'SCAM_ATTEMPT', 'PROHIBITED_ITEM', 'FALSE_INFORMATION',
        'INAPPROPRIATE_CONTENT', 'SPAM', 'PAYMENT_ISSUE', 'APP_BUG', 'SCREEN_BUG', 'OTHER'));

ALTER TABLE reports ADD COLUMN IF NOT EXISTS screen_route VARCHAR(200);
