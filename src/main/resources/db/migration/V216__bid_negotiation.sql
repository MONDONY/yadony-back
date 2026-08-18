-- Fil de negociation porte par le bid lui-meme (package matching/).
-- bids.status est VARCHAR(20) via EnumType.STRING et n'a AUCUNE contrainte CHECK :
-- l'ajout de NEGOTIATING (11 caracteres) ne demande aucune modification DDL.
-- Meme gabarit que V71/V72/V73, qui ont introduit les statuts precedents.
COMMENT ON COLUMN bids.status IS
  'BidStatus: PENDING | AWAITING_PAYMENT | PAYMENT_ESCROWED | ACCEPTED | HANDED_OVER | IN_TRANSIT | ARRIVED | REJECTED | CANCELLED | COMPLETED | NO_SHOW | PARCEL_REFUSED | EXPIRED | NEGOTIATING';

ALTER TABLE bids
  ADD COLUMN negotiated_gross_eur  NUMERIC(10,2),
  ADD COLUMN negotiation_round     INT NOT NULL DEFAULT 0,
  ADD COLUMN sender_last_read_at   TIMESTAMP WITH TIME ZONE,
  ADD COLUMN traveler_last_read_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN bids.negotiated_gross_eur IS
  'Brut fige a l accord : ce que l expediteur paie. negotiated_net_eur porte le net voyageur.';

-- Messages du fil : APPEND ONLY, jamais d UPDATE ni de DELETE.
CREATE TABLE bid_negotiation_messages (
  id                 UUID PRIMARY KEY,
  bid_id             UUID NOT NULL REFERENCES bids(id),
  author_id          UUID NOT NULL REFERENCES users(id),
  kind               VARCHAR(16) NOT NULL,
  proposed_gross_eur NUMERIC(10,2),
  body               VARCHAR(280),
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_bid_nego_msg_bid ON bid_negotiation_messages (bid_id, created_at);

-- Articles hors grille : decrits ET chiffres par l expediteur, puisque le
-- voyageur ne les a jamais tarifes. amount_eur est un montant UNITAIRE.
CREATE TABLE bid_custom_items (
  id         UUID PRIMARY KEY,
  bid_id     UUID NOT NULL REFERENCES bids(id),
  label      VARCHAR(100) NOT NULL,
  quantity   INT NOT NULL,
  amount_eur NUMERIC(10,2) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_bid_custom_items_bid ON bid_custom_items (bid_id);
