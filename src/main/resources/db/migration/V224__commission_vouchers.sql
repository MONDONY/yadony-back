-- Lot 3 (2026-08-19/20) : le parrainage ne verse plus d'argent, il donne un bon de
-- réduction de commission consommable une fois. Table immuable (pas de soft delete) :
-- un bon est soit disponible soit consommé, jamais réécrit après coup.
CREATE TABLE commission_vouchers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    factor NUMERIC(3,2) NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    consumed_on_bid_id UUID,
    source_invitation_id UUID NOT NULL,
    CONSTRAINT commission_vouchers_source_invitation_unique UNIQUE (source_invitation_id)
);

CREATE INDEX idx_commission_vouchers_user_active
    ON commission_vouchers (user_id)
    WHERE consumed_at IS NULL;
