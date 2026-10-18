-- Commission par paliers (spec 2026-10-18-commission-paliers-design.md) : grille marginale datée, minimum par
-- devise, figée sur chaque objet qui porte un prix montré à l'autre partie.
--
-- Une ligne = une version de la grille. La grille en vigueur à l'instant t est la plus récente dont
-- effective_from <= t et deleted_at IS NULL. Aucune grille n'est insérée ici : tant que le propriétaire n'en a
-- pas programmé, le back calcule exactement comme avant (colonnes commission_grid_id à NULL = ancien calcul).
--
-- tiers    : [{"fromEur":"0","rate":"0.07"}, ...] triées par fromEur croissant, bornes en EUR.
-- minimums : {"EUR":"1.00","XOF":"650", ...} commission minimale par devise, dans l'unité de la devise.
-- created_by : id de l'admin, sans clé étrangère comme wallet_topup_codes (V322).
-- effective_from est un TIMESTAMP sans fuseau stocké en UTC, comme le reste du schéma.

CREATE TABLE commission_grids (
    id             UUID PRIMARY KEY,
    effective_from TIMESTAMP    NOT NULL,
    tiers          JSONB        NOT NULL,
    minimums       JSONB        NOT NULL,
    note           VARCHAR(500),
    created_by     UUID         NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    deleted_at     TIMESTAMP
);

CREATE INDEX idx_commission_grids_effective_from ON commission_grids (effective_from) WHERE deleted_at IS NULL;

-- Une grille en vigueur (effective_from <= maintenant, UTC) est immuable, y compris sa suppression logique :
-- les offres, fils et demandes qui l'ont figée doivent pouvoir la relire telle quelle. Seule une grille
-- programmée peut être annulée (UPDATE deleted_at). Aucune grille ne se supprime physiquement.
CREATE OR REPLACE FUNCTION prevent_effective_commission_grid_change()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.effective_from <= (now() AT TIME ZONE 'UTC') THEN
        RAISE EXCEPTION 'commission_grid_immutable' USING DETAIL = 'operation ' || TG_OP || ' interdite sur la grille ' || OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_commission_grids_immutable
    BEFORE UPDATE OR DELETE ON commission_grids
    FOR EACH ROW EXECUTE FUNCTION prevent_effective_commission_grid_change();

-- Grille figée à la création. NULL = objet antérieur : ancien calcul (taux figé, minimum espèces actuel).
ALTER TABLE bids                ADD COLUMN commission_grid_id UUID REFERENCES commission_grids(id);
ALTER TABLE package_requests    ADD COLUMN commission_grid_id UUID REFERENCES commission_grids(id);
ALTER TABLE negotiation_threads ADD COLUMN commission_grid_id UUID REFERENCES commission_grids(id);

-- Taux spécial et facteur du bon de parrainage figés sur le bid (NULL = aucun).
ALTER TABLE bids ADD COLUMN commission_override_rate  NUMERIC(7,6);
ALTER TABLE bids ADD COLUMN commission_voucher_factor NUMERIC(4,3);

-- Les taux figés deviennent des taux effectifs (commission / net) à 6 décimales : (4,3) ne les tiendrait pas.
-- Élargissement seul : les valeurs existantes sont conservées (0.050 -> 0.050000) et le CHECK
-- bids_commission_rate_range (V118, [0, 1[) reste valide.
ALTER TABLE bids                ALTER COLUMN commission_rate TYPE NUMERIC(7,6);
ALTER TABLE negotiation_threads ALTER COLUMN commission_rate TYPE NUMERIC(7,6);
ALTER TABLE promo_redemptions   ALTER COLUMN applied_rate    TYPE NUMERIC(7,6);
