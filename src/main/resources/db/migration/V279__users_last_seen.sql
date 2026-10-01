-- FLUTTER-4H (partie 2) : dernière connexion affichée au jour près sur le profil
-- public. last_seen_at est écrite à l'ouverture de l'app (GET /auth/me), au plus
-- une fois par quart d'heure. show_last_seen permet de la masquer ; visible par
-- défaut, comme sur les autres places de marché.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS show_last_seen BOOLEAN NOT NULL DEFAULT TRUE;
