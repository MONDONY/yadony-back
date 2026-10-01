-- FLUTTER-4H : l'utilisateur choisit d'afficher son pays de résidence sur son
-- profil public. Opt-in explicite : masqué par défaut pour tous les comptes.
ALTER TABLE users ADD COLUMN IF NOT EXISTS show_residence_country BOOLEAN NOT NULL DEFAULT FALSE;
