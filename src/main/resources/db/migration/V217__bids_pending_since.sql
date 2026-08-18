-- Horloge du timeout « demande sans reponse du voyageur ».
--
-- BidTimeoutScheduler annule les bids PENDING dont le voyageur n a pas repondu
-- dans les 24 h. Le critere etait created_at, ce qui suffisait tant qu un bid
-- naissait PENDING. Un accord de negociation, lui, entre dans la file du
-- voyageur BIEN APRES sa creation : le fil peut avoir vecu 72 h avant l accord,
-- et le bid etait alors annule au tick suivant, avant meme que le voyageur ait
-- pu regler la commission.
--
-- pending_since porte donc l instant ou le bid est entre dans la file du
-- voyageur. NULL = confondu avec created_at (cas de tous les bids existants et
-- de toute demande ferme), d ou l absence de backfill.
ALTER TABLE bids ADD COLUMN pending_since TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN bids.pending_since IS
  'Instant ou le bid est entre dans la file d attente du voyageur. NULL = created_at.';
