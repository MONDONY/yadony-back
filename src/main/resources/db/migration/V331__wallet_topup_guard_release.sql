-- V331 : libération de la garde « une recharge en cours par utilisateur et par devise ».
--
-- L'index unique partiel uq_pawapay_ops_live_wallet_topup (V260) interdisait toute seconde
-- recharge tant que la première restait non terminale, sans limite de durée : une demande
-- jamais validée (SMS non reçu, PIN oublié) bloquait l'utilisateur jusqu'à ce que pawaPay la
-- déclare échouée. Le retirer rouvrirait la course entre deux requêtes simultanées (la garde
-- applicative lit puis écrit dans une autre transaction) : l'index doit rester l'arbitre.
--
-- On le garde donc, mais il ne compte plus que les recharges dont topup_guard est vrai.
-- WalletMobileMoneyTopupService passe topup_guard à faux pour les recharges ouvertes de plus
-- de 15 min AVANT de tester la garde : elles ne bloquent plus, et leur éventuelle validation
-- tardive crédite quand même (le statut, lui, n'est pas touché).
ALTER TABLE pawapay_operations
    ADD COLUMN topup_guard BOOLEAN NOT NULL DEFAULT TRUE;

DROP INDEX IF EXISTS uq_pawapay_ops_live_wallet_topup;
CREATE UNIQUE INDEX uq_pawapay_ops_live_wallet_topup ON pawapay_operations (user_id, purpose, currency)
    WHERE purpose = 'WALLET_TOPUP' AND topup_guard
      AND status IN ('CREATED', 'ACCEPTED', 'PROCESSING', 'ENQUEUED', 'IN_RECONCILIATION');
