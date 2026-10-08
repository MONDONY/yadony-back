-- GET /wallet/balance cherche les items de remboursement par recharge
-- (wallet_transaction_id IN (...)), deux fois par appel : statut affiché dans
-- l'historique et rejeu du ledger de chaque devise. V229 n'indexait que
-- refund_request_id et payment_intent_id, d'où un parcours complet de la table
-- à chaque affichage du portefeuille (test de charge du 08/10/2026).
CREATE INDEX IF NOT EXISTS idx_wallet_refund_request_items_wallet_tx
    ON wallet_refund_request_items (wallet_transaction_id);
