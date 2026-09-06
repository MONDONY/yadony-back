-- V250 : retrait des colonnes de confort payments.pawapay_{deposit,payout,refund}_id (V248).
-- Elles dupliquaient pawapay_operations.payment_id (le lien qui fait autorité), n'avaient qu'un
-- seul lecteur (le détail admin, qui lit désormais pawapay_operations) et imposaient à chaque
-- écriture toute la discipline « jamais de setter sur l'entité après un claim bulk ».
-- Aucune donnée perdue : les identifiants restent dans pawapay_operations.
ALTER TABLE payments
    DROP COLUMN IF EXISTS pawapay_deposit_id,
    DROP COLUMN IF EXISTS pawapay_payout_id,
    DROP COLUMN IF EXISTS pawapay_refund_id;
