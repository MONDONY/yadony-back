-- V310 : identifiant du Transfer Stripe qui a versé le net au voyageur.
--
-- La clé d'idempotence Stripe `transfer-<paymentId>` expire au bout de 24 h. Avant tout
-- nouveau Transfer (livraison, versement tardif, libération forcée), le back cherche chez
-- Stripe un Transfer déjà émis pour ce paiement : s'il existe, la base est réalignée sur lui
-- (statut RELEASED et identifiant ci-dessous) au lieu d'en créer un second.
-- Colonne facultative : les versements antérieurs restent à NULL.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS stripe_transfer_id VARCHAR(255);
