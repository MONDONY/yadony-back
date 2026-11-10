-- Modals : moment, ciblage et fréquence (étape 1).
--
-- Toutes les colonnes ajoutées portent un défaut qui reproduit le comportement d'avant : les
-- campagnes existantes (dont la vidéo d'accueil) gardent « jusqu'à N fois », « s'arrête après une
-- réponse », aucune condition, aucune situation, toutes les versions et toutes les plateformes.
ALTER TABLE video_modal_campaigns ADD COLUMN frequency VARCHAR(20) NOT NULL DEFAULT 'UP_TO_N';
-- Obligatoire pour EVERY_N_DAYS seulement (1 à 365).
ALTER TABLE video_modal_campaigns ADD COLUMN frequency_days INTEGER;
ALTER TABLE video_modal_campaigns ADD COLUMN stop_after_answer BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE video_modal_campaigns ADD COLUMN allow_dont_show_again BOOLEAN NOT NULL DEFAULT FALSE;
-- Plus haut passe d'abord ; à égalité, version publiée la plus récente.
ALTER TABLE video_modal_campaigns ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE video_modal_campaigns ADD COLUMN connected_only BOOLEAN NOT NULL DEFAULT FALSE;
-- Arbre de règles de la Diffusion (JSON canonique de AudienceRule.Group) ; NULL = aucune condition.
ALTER TABLE video_modal_campaigns ADD COLUMN conditions TEXT;
ALTER TABLE video_modal_campaigns ADD COLUMN min_app_version VARCHAR(20);
ALTER TABLE video_modal_campaigns ADD COLUMN max_app_version VARCHAR(20);
-- ["ANDROID","IOS"] ; liste vide = toutes les plateformes.
ALTER TABLE video_modal_campaigns ADD COLUMN platforms TEXT NOT NULL DEFAULT '[]';
-- Tirage stable par personne (hash de l'utilisateur et de la campagne) : 100 = tout le monde.
ALTER TABLE video_modal_campaigns ADD COLUMN sample_percent INTEGER NOT NULL DEFAULT 100;
-- [{"kind":"OFFER_UNANSWERED","value":24}] : constatées par le serveur, toutes doivent être vraies.
ALTER TABLE video_modal_campaigns ADD COLUMN situations TEXT NOT NULL DEFAULT '[]';

ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_frequency_check
        CHECK (frequency IN ('ONCE', 'UP_TO_N', 'EVERY_OPEN', 'EVERY_N_DAYS'));
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_frequency_days_check
        CHECK (frequency_days IS NULL OR frequency_days BETWEEN 1 AND 365);
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_every_n_days_check
        CHECK (frequency <> 'EVERY_N_DAYS' OR frequency_days IS NOT NULL);
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_sample_percent_check
        CHECK (sample_percent BETWEEN 1 AND 100);

-- « Ne plus afficher » : réponse définitive pour la version de la ligne, sans changer le statut
-- (COMPLETED > OPENED > DISMISSED > SHOWN) ni le compteur d'affichages.
ALTER TABLE video_modal_responses ADD COLUMN dont_show_again_at TIMESTAMP WITH TIME ZONE;

-- Situation « retour après une absence » : last_seen_at est réécrite dès l'ouverture de l'app
-- (GET /auth/me), donc déjà égale à maintenant quand l'app demande les modals. On garde donc la
-- dernière coupure : absence_started_at = dernière visite avant une pause d'au moins 6 h,
-- returned_at = moment du retour. Nulles tant qu'aucune coupure n'a été vue.
ALTER TABLE users ADD COLUMN absence_started_at TIMESTAMP;
ALTER TABLE users ADD COLUMN returned_at TIMESTAMP;
