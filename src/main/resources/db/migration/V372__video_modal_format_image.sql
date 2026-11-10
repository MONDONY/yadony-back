-- Modals : format (feuille du bas, carte centrée, plein écran), taille réglable et image (INFO).
--
-- Les lignes existantes ne sont pas touchées : elles prennent les défauts (feuille du bas, sans
-- image, taille automatique) et gardent donc exactement leur rendu actuel.
ALTER TABLE video_modal_campaigns ADD COLUMN layout VARCHAR(20) NOT NULL DEFAULT 'BOTTOM_SHEET';
-- Hauteur maximale en % de l'écran ; NULL = taille automatique selon le contenu.
ALTER TABLE video_modal_campaigns ADD COLUMN size_percent INTEGER;
ALTER TABLE video_modal_campaigns ADD COLUMN image_mode VARCHAR(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE video_modal_campaigns ADD COLUMN image_fit VARCHAR(10) NOT NULL DEFAULT 'COVER';
-- Clé R2 (video-modals/{campagne}/{horodatage}.{ext}), jamais une URL : l'URL lisible est
-- présignée à chaque lecture. Remplie par l'envoi de fichier, jamais saisie à la main.
ALTER TABLE video_modal_campaigns ADD COLUMN image_key VARCHAR(500);
ALTER TABLE video_modal_campaigns ADD COLUMN image_alt_fr VARCHAR(200);
ALTER TABLE video_modal_campaigns ADD COLUMN image_alt_en VARCHAR(200);

ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_layout_check
        CHECK (layout IN ('BOTTOM_SHEET', 'CENTERED', 'FULLSCREEN'));
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_size_percent_check
        CHECK (size_percent IS NULL OR size_percent BETWEEN 30 AND 100);
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_image_mode_check
        CHECK (image_mode IN ('NONE', 'TOP', 'POSTER'));
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_image_fit_check
        CHECK (image_fit IN ('COVER', 'CONTAIN'));
-- Une image affichée suppose une image envoyée ; l'image n'existe que pour INFO.
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_image_required_check
        CHECK (image_mode = 'NONE' OR (image_key IS NOT NULL AND type = 'INFO'));
