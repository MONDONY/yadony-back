-- V331 : l'index unique partiel uq_pawapay_ops_live_wallet_topup (V260) interdisait toute
-- seconde recharge tant que la première restait non terminale, sans limite de durée. Une
-- demande jamais validée (SMS non reçu, PIN oublié) bloquait alors l'utilisateur jusqu'à ce
-- que pawaPay la déclare échouée. La garde applicative de WalletMobileMoneyTopupService est
-- désormais bornée à 15 min (recharges non terminales créées il y a moins de 15 min) : un
-- index unique sans borne de temps la contredirait, il est retiré.
DROP INDEX IF EXISTS uq_pawapay_ops_live_wallet_topup;
