-- Origine d'un signalement : USER (fait depuis l'app par un utilisateur) ou SYSTEM (créé par
-- Yadony). Premier signalement SYSTEM : la description d'un colis qui évoque l'envoi d'argent,
-- interdit par l'article 4 des CGU. Dans les deux cas reporter_id peut être vide ; l'admin
-- affiche « Détection automatique » pour SYSTEM au lieu de « Inconnu ».
ALTER TABLE reports ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'USER';
ALTER TABLE reports ADD CONSTRAINT chk_reports_source CHECK (source IN ('USER', 'SYSTEM'));

-- Un seul signalement système ouvert par cible : la détection est rejouée à chaque
-- modification d'une demande, elle n'empile pas de doublons, même sur deux écritures
-- simultanées.
CREATE UNIQUE INDEX uq_reports_system_open_target ON reports (target_type, target_id)
    WHERE source = 'SYSTEM' AND status = 'OPEN' AND deleted_at IS NULL;

COMMENT ON COLUMN reports.source IS 'USER | SYSTEM (ReportSource)';
