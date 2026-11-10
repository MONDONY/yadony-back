-- Modals « Information » : une campagne a un type, VIDEO (existant) ou INFO (feuille du bas
-- avec icône, texte et un ou deux boutons dont chacun a une action).
--
-- Les lignes existantes restent des VIDEO (défaut) et ne sont pas touchées. Un INFO n'a pas de
-- vidéo : youtube_url et youtube_video_id deviennent nullables, mais un VIDEO les garde
-- obligatoires (contrainte ci-dessous).
ALTER TABLE video_modal_campaigns ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'VIDEO';
ALTER TABLE video_modal_campaigns ADD COLUMN body_fr VARCHAR(500);
ALTER TABLE video_modal_campaigns ADD COLUMN body_en VARCHAR(500);
ALTER TABLE video_modal_campaigns ADD COLUMN icon VARCHAR(20);
-- Actions JSON {"kind":"SCREEN|URL|CLOSE","screen":"SEARCH"|null,"url":"https://…"|null},
-- écrites par l'API (convertisseur JPA, validées par le service) : TEXT comme highlights.
ALTER TABLE video_modal_campaigns ADD COLUMN primary_action TEXT;
ALTER TABLE video_modal_campaigns ADD COLUMN secondary_action TEXT;

ALTER TABLE video_modal_campaigns ALTER COLUMN youtube_url DROP NOT NULL;
ALTER TABLE video_modal_campaigns ALTER COLUMN youtube_video_id DROP NOT NULL;

ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_type_check CHECK (type IN ('VIDEO', 'INFO'));
-- VIDEO : lien et identifiant YouTube obligatoires (comme avant V369).
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_video_required_check
        CHECK (type <> 'VIDEO' OR (youtube_url IS NOT NULL AND youtube_video_id IS NOT NULL));
-- INFO : texte et bouton principal obligatoires.
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_info_required_check
        CHECK (type <> 'INFO' OR (body_fr IS NOT NULL AND primary_action IS NOT NULL));
