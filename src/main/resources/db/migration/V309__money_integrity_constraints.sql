-- V309 : verrous en base sur l'argent (lot C de l'audit de cohérence du 2026-10-05).
--
-- La sonde MoneyIntegrityMonitor détecte une incohérence après coup ; ces contraintes
-- empêchent de l'écrire. Données de staging et de prod vérifiées conformes à chacune
-- d'elles le 2026-10-09, avant écriture de cette migration.

-- 1. Paiements : le remboursé et la commission restent dans le montant payé.
ALTER TABLE payments
    ADD CONSTRAINT chk_payments_refunded_within_amount
        CHECK (refunded_amount IS NULL OR (refunded_amount >= 0 AND refunded_amount <= amount)),
    ADD CONSTRAINT chk_payments_commission_within_amount
        CHECK (commission_amount <= amount);

-- 2. Grand livre du wallet : aucun mouvement ne laisse un solde négatif, et le signe suit
--    le type (crédit positif, débit négatif ; zéro toléré partout). Un type ajouté plus tard
--    n'est pas contraint tant qu'on ne l'ajoute pas ici.
ALTER TABLE wallet_transactions
    ADD CONSTRAINT chk_wallet_tx_balance_after_non_negative
        CHECK (balance_after >= 0),
    ADD CONSTRAINT chk_wallet_tx_sign_matches_type
        CHECK (
            NOT (type IN ('TOP_UP', 'REFUND', 'REFERRAL_REWARD', 'ADMIN_CREDIT') AND amount < 0)
            AND NOT (type IN ('BID_PAYMENT', 'COMMISSION_DEDUCTED', 'ADMIN_REFUND_OUT', 'SELF_REFUND_OUT',
                              'FORFEITED_ON_DELETION', 'ADMIN_DEBIT') AND amount > 0)
        );

-- 3. Grand livre en ajout seul : une erreur se corrige par un mouvement compensatoire, jamais
--    en réécrivant l'histoire. Seuls UPDATE et DELETE sont refusés : TRUNCATE (opération de
--    schéma, jamais faite par l'application) reste possible pour le nettoyage des tests e2e.
--    Une future migration de données qui doit vraiment réécrire des lignes encadre son UPDATE
--    par ALTER TABLE wallet_transactions DISABLE / ENABLE TRIGGER trg_wallet_transactions_append_only.
CREATE OR REPLACE FUNCTION wallet_transactions_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'wallet_transactions est en ajout seul : % refusé (corriger par un mouvement compensatoire)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wallet_transactions_append_only
    BEFORE UPDATE OR DELETE ON wallet_transactions
    FOR EACH ROW EXECUTE FUNCTION wallet_transactions_append_only();

-- 4. pawaPay : montant strictement positif, et une transaction du prestataire ne peut être
--    rattachée qu'à une seule de nos opérations.
ALTER TABLE pawapay_operations
    ADD CONSTRAINT chk_pawapay_ops_amount_positive
        CHECK (amount > 0);

CREATE UNIQUE INDEX uq_pawapay_ops_provider_transaction
    ON pawapay_operations (provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;
