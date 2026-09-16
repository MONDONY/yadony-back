-- V258 : remboursement partiel du wallet et solde non-cash perdu à la suppression de compte.
-- Colonnes additives uniquement ; les colonnes refund_eligible_* de wallet_accounts ne sont
-- plus lues ni écrites par l'application mais restent en place (retrait dans une migration ultérieure).

ALTER TABLE wallet_refund_requests
    ADD COLUMN parent_request_id UUID NULL REFERENCES wallet_refund_requests (id);
CREATE INDEX idx_wallet_refund_requests_parent ON wallet_refund_requests (parent_request_id);

ALTER TABLE wallet_refund_request_items
    ADD COLUMN failure_reason VARCHAR(60) NULL;

ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD',
             'ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION')
);
