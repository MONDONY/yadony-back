-- Le KYC n'est plus lie a Stripe Identity : la colonne de session devient neutre, et chaque
-- ligne porte le fournisseur qui l'a produite. C'est ce qui permet, apres la bascule vers
-- Didit, de continuer a relire une session Stripe historique — et plus tard de supprimer
-- l'implementation Stripe sans toucher aux donnees.
ALTER TABLE kyc_schema.kyc_verifications
    RENAME COLUMN stripe_verification_session_id TO verification_session_id;

-- DEFAULT 'STRIPE' : tout l'historique vient de Stripe Identity, le retro-remplissage est
-- donc exact. Le defaut reste en place pour ne pas casser une insertion qui ignorerait la
-- colonne, notamment dans les tests de migration anterieurs.
ALTER TABLE kyc_schema.kyc_verifications
    ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'STRIPE';
