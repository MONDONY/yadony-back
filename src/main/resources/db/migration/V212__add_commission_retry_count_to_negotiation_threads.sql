-- Discriminant de réessai pour l'idempotence Stripe du règlement de commission
-- cash d'un thread de négociation, même convention que bids.commission_retry_count
-- (V74). Sans lui, la clé "nego_commission_" + threadId reste figée pour
-- toujours : après un échec (carte refusée, 3DS jamais complétée), Stripe rejoue
-- indéfiniment la première réponse en cache (ex. "requires_action") au lieu de
-- retenter un débit réel, et le voyageur ne peut plus jamais régler sa commission.
ALTER TABLE negotiation_threads ADD COLUMN IF NOT EXISTS commission_retry_count INTEGER NOT NULL DEFAULT 0;
