-- V266 : correction manuelle du solde wallet par un administrateur.
--
-- Deux types de mouvement : ADMIN_CREDIT et ADMIN_DEBIT. La liste reprend exactement
-- celle de V258 et y ajoute les deux nouvelles valeurs.
--
-- admin_reason / admin_actor_id : motif interne et admin auteur de la correction,
-- NULL sur tout autre mouvement. Le motif n'est jamais montré à l'utilisateur.

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD',
             'ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION',
             'ADMIN_CREDIT','ADMIN_DEBIT')
);

ALTER TABLE wallet_transactions ADD COLUMN IF NOT EXISTS admin_reason VARCHAR(500) NULL;
ALTER TABLE wallet_transactions ADD COLUMN IF NOT EXISTS admin_actor_id UUID NULL;
