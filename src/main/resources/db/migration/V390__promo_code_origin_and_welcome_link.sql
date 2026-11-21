-- Origine des codes promo : distingue les codes créés par l'admin, les récompenses de
-- parrainage (code personnel du parrain, V321) et les codes de bienvenue du filleul.
-- Le DEFAULT 'ADMIN' reste en place : tout code créé sans origine explicite est un code admin.
ALTER TABLE promo_codes ADD COLUMN origin VARCHAR(30) NOT NULL DEFAULT 'ADMIN';

UPDATE promo_codes
   SET origin = CASE WHEN owner_user_id IS NOT NULL THEN 'REFERRAL_REWARD' ELSE 'ADMIN' END;

ALTER TABLE promo_codes
    ADD CONSTRAINT promo_codes_origin_check
        CHECK (origin IN ('ADMIN', 'REFERRAL_REWARD', 'REFERRAL_WELCOME'));

-- Code de bienvenue offert au filleul, lié à son invitation.
ALTER TABLE referral_invitations
    ADD COLUMN welcome_promo_code_id UUID REFERENCES promo_codes(id);
