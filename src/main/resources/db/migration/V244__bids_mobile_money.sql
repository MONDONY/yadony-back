-- V244: bids mobile money (rail pawaPay).
-- 1. payment_method accepte MOBILE_MONEY (WAVE/ORANGE_MONEY restent pour l'historique).
ALTER TABLE public.bids DROP CONSTRAINT IF EXISTS bids_payment_method_check;
ALTER TABLE public.bids
    ADD CONSTRAINT bids_payment_method_check
    CHECK (payment_method IN ('STRIPE', 'CASH', 'WAVE', 'ORANGE_MONEY', 'MOBILE_MONEY'));

-- 2. Le numéro payeur est désormais chiffré (EncryptedStringConverter) : colonne élargie,
--    et les valeurs legacy en clair (bids WAVE/ORANGE_MONEY, jamais payables) sont vidées
--    pour ne pas faire échouer le déchiffrement au chargement.
UPDATE public.bids SET mobile_money_phone = NULL, mobile_money_country_code = NULL
 WHERE payment_method IN ('WAVE', 'ORANGE_MONEY');
ALTER TABLE public.bids ALTER COLUMN mobile_money_phone TYPE VARCHAR(255);

-- 3. Expiration des bids en attente de paiement mobile money.
CREATE INDEX IF NOT EXISTS idx_bids_awaiting_payment_expiry
    ON public.bids (awaiting_payment_expires_at) WHERE status = 'AWAITING_PAYMENT';
