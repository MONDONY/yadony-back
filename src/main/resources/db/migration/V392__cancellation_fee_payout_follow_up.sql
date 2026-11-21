-- V392 : suivi du versement d'un dédommagement d'annulation en attente.
--
-- payout_pending_reason : dernier motif de l'attente. Un nouveau motif sur une ligne déjà en attente est
-- enregistré et audité (un changement de cause doit se voir), le même motif n'écrit rien (pas d'audit horaire).
-- payout_rearmed_at : relance d'un administrateur (POST /admin/cancellation-fees/{id}/retry-payout). Seuls les
-- versements mobile money échoués APRÈS cette date comptent pour le plafond d'essais automatiques.
ALTER TABLE cancellation_fees ADD COLUMN payout_pending_reason VARCHAR(255);
ALTER TABLE cancellation_fees ADD COLUMN payout_rearmed_at TIMESTAMPTZ;
