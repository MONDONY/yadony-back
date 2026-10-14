-- Parrainage : la récompense du parrain devient un CHOIX (5 € sur le solde ou code
-- personnel de 5 % sur le prochain envoi) au lieu du bon -50 % octroyé d'office.
--
-- referral_invitations.reward_status :
--   NONE      le filleul n'a pas encore fait de premier envoi livré ;
--   AVAILABLE le premier envoi est livré, le parrain n'a pas encore choisi ;
--   CHOSEN    le parrain a reçu sa récompense (reward_choice dit laquelle).
--
-- Les invitations déjà REWARDED ont reçu un bon -50 % (commission_vouchers) : elles
-- passent en CHOSEN / COMMISSION_VOUCHER pour ne jamais redevenir choisissables. Ce
-- choix historique n'est pas proposé par l'API.
ALTER TABLE referral_invitations
    ADD COLUMN reward_status        VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN reward_choice        VARCHAR(30),
    ADD COLUMN reward_available_at  TIMESTAMP,
    ADD COLUMN reward_chosen_at     TIMESTAMP,
    ADD COLUMN reward_promo_code_id UUID REFERENCES promo_codes(id);

UPDATE referral_invitations
   SET reward_status       = 'CHOSEN',
       reward_choice       = 'COMMISSION_VOUCHER',
       reward_available_at = rewarded_at,
       reward_chosen_at    = rewarded_at
 WHERE status = 'REWARDED';

ALTER TABLE referral_invitations
    ADD CONSTRAINT referral_invitations_reward_status_check
        CHECK (reward_status IN ('NONE', 'AVAILABLE', 'CHOSEN')),
    ADD CONSTRAINT referral_invitations_reward_choice_check
        CHECK (reward_choice IS NULL
               OR reward_choice IN ('WALLET_CREDIT', 'DISCOUNT_CODE', 'COMMISSION_VOUCHER')),
    -- Un choix n'existe que pour une récompense choisie, et réciproquement.
    ADD CONSTRAINT referral_invitations_reward_choice_coherence
        CHECK ((reward_status = 'CHOSEN') = (reward_choice IS NOT NULL)),
    -- Le code personnel n'existe que pour le choix DISCOUNT_CODE.
    ADD CONSTRAINT referral_invitations_reward_promo_coherence
        CHECK (reward_promo_code_id IS NULL OR reward_choice = 'DISCOUNT_CODE');

-- Un code personnel ne récompense qu'une seule invitation.
CREATE UNIQUE INDEX uq_referral_invitations_reward_promo
    ON referral_invitations(reward_promo_code_id) WHERE reward_promo_code_id IS NOT NULL;

-- promo_codes.owner_user_id : NULL = code global (comportement historique), sinon seul
-- ce compte peut l'utiliser.
-- promo_codes.discount_kind :
--   COMMISSION_POINTS rate est retranché en points du taux de commission (historique) ;
--   PRICE_PERCENT     rate est une remise en pourcentage du prix payé par l'expéditeur,
--                     financée sur la commission et plafonnée à celle-ci.
ALTER TABLE promo_codes
    ADD COLUMN owner_user_id UUID REFERENCES users(id),
    ADD COLUMN discount_kind VARCHAR(20) NOT NULL DEFAULT 'COMMISSION_POINTS';

ALTER TABLE promo_codes
    ADD CONSTRAINT promo_codes_discount_kind_check
        CHECK (discount_kind IN ('COMMISSION_POINTS', 'PRICE_PERCENT'));

CREATE INDEX idx_promo_codes_owner ON promo_codes(owner_user_id) WHERE owner_user_id IS NOT NULL;
