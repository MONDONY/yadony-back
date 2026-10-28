-- Parrainage : un compte n'est parrainé qu'une fois. La règle n'était tenue que par le code
-- (lecture puis écriture) : deux saisies simultanées du code créaient deux invitations pour le
-- même filleul, et la récompense du parrain se perdait (lecture à résultat unique en échec).
--
-- L'index n'est posé que si aucun doublon n'existe déjà : un doublon historique ferait échouer
-- la migration, donc le déploiement. Dans ce cas un avertissement est émis et la règle reste
-- tenue par le verrou du filleul (ReferralService#redeemCode) ; les doublons sont à traiter à
-- la main avant de rejouer la création de l'index.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM referral_invitations
               WHERE referee_user_id IS NOT NULL AND deleted_at IS NULL
               GROUP BY referee_user_id HAVING COUNT(*) > 1) THEN
        RAISE WARNING 'referral_invitations : filleuls en double, index uq_referral_invitations_referee non créé';
    ELSE
        CREATE UNIQUE INDEX uq_referral_invitations_referee ON referral_invitations (referee_user_id)
            WHERE referee_user_id IS NOT NULL AND deleted_at IS NULL;
    END IF;
END $$;
