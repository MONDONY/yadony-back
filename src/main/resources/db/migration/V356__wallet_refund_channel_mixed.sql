-- Remboursement du solde : une devise portant des recharges carte ET mobile money repart
-- automatiquement vers chaque rail d'origine, dans une seule demande AUTOMATIC_MIXED (au lieu
-- d'un ticket manuel portant tout le solde, non-cash compris).
ALTER TABLE wallet_refund_requests DROP CONSTRAINT IF EXISTS chk_wallet_refund_requests_channel;
ALTER TABLE wallet_refund_requests ADD CONSTRAINT chk_wallet_refund_requests_channel
    CHECK (channel IN ('AUTOMATIC_STRIPE', 'AUTOMATIC_PAWAPAY', 'AUTOMATIC_MIXED', 'MANUAL_ADMIN'));
