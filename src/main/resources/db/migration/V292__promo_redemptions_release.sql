-- Code promo rendu quand l'envoi se termine sans livraison et que Yadony ne garde pas la
-- commission remisée (remboursée en entier ou jamais prélevée) : annulation, refus,
-- expiration, annulation de trajet, no-show du voyageur (hors espèces).
--
-- La ligne de rachat n'est jamais supprimée : elle est marquée libérée. Les limites
-- (per_user_limit, max_redemptions via redeemed_count) ne comptent que les rachats actifs
-- (released_at IS NULL). Un nouveau rachat du même code sur le MÊME bid réactive la ligne
-- (UNIQUE(promo_code_id, bid_id) inchangé).

ALTER TABLE promo_redemptions ADD COLUMN IF NOT EXISTS released_at    TIMESTAMPTZ NULL;
ALTER TABLE promo_redemptions ADD COLUMN IF NOT EXISTS release_reason VARCHAR(40) NULL;

-- Libération : recherche des rachats actifs d'un bid (l'index UNIQUE commence par promo_code_id).
CREATE INDEX IF NOT EXISTS idx_promo_redemptions_bid_active
    ON promo_redemptions (bid_id) WHERE released_at IS NULL;

-- Limite par utilisateur : comptage des rachats actifs d'un code pour un utilisateur.
CREATE INDEX IF NOT EXISTS idx_promo_redemptions_user_active
    ON promo_redemptions (promo_code_id, user_id) WHERE released_at IS NULL;
