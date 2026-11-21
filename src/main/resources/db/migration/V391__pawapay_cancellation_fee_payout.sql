-- V391 : versement pawaPay du dédommagement d'annulation au voyageur (rail mobile money).
--
-- L'opération est rattachée à la retenue (cancellation_fees.id), jamais au paiement du colis : ce
-- paiement est déjà remboursé (en partie) à l'expéditeur. Un PAYOUT porté par payment_id heurterait
-- uq_pawapay_ops_live_per_payment (un seul versement vivant par paiement), serait pris par les
-- écouteurs du paiement principal pour le versement du colis, et ferait lever l'invariant INV-12
-- (VERSE_ET_REMBOURSE). Calqué sur bid_supplement_id (V338) et bid_amendment_id (V347).
ALTER TABLE pawapay_operations ADD COLUMN cancellation_fee_id UUID NULL REFERENCES cancellation_fees(id);

ALTER TABLE pawapay_operations DROP CONSTRAINT chk_pawapay_ops_purpose;
ALTER TABLE pawapay_operations ADD CONSTRAINT chk_pawapay_ops_purpose
    CHECK (purpose IN ('BID_PAYMENT', 'WALLET_TOPUP', 'WALLET_REFUND', 'BID_SUPPLEMENT', 'BID_AMENDMENT',
                       'CANCELLATION_FEE'));
ALTER TABLE pawapay_operations ADD CONSTRAINT chk_pawapay_ops_purpose_cancellation_fee
    CHECK ((purpose = 'CANCELLATION_FEE') = (cancellation_fee_id IS NOT NULL));
-- Un dédommagement ne se verse que par un PAYOUT (ni dépôt, ni remboursement).
ALTER TABLE pawapay_operations ADD CONSTRAINT chk_pawapay_ops_cancellation_fee_payout
    CHECK (purpose <> 'CANCELLATION_FEE' OR kind = 'PAYOUT');

-- Au plus un versement vivant ou abouti par retenue : un rejeu ou un nouvel essai reprend l'opération
-- existante, jamais un second versement. Les opérations mortes (FAILED, SUBMIT_REJECTED) n'y comptent
-- pas : un nouvel essai reste possible. Calqué sur uq_pawapay_ops_live_per_supplement (V338).
CREATE UNIQUE INDEX uq_pawapay_ops_live_per_cancellation_fee ON pawapay_operations (cancellation_fee_id, kind)
    WHERE status NOT IN ('FAILED', 'SUBMIT_REJECTED') AND cancellation_fee_id IS NOT NULL;
