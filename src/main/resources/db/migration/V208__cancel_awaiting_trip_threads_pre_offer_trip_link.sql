-- V208: threads bloqués en AWAITING_TRIP par l'ancien flux (trajet lié après
-- acceptation) deviennent inatteignables pour les nouveaux threads une fois
-- start() exige un trajet dès la création. On les annule plutôt que de les
-- laisser bloqués indéfiniment sans chemin de sortie côté mobile (l'écran
-- LinkTripScreen n'y navigue plus automatiquement, cf. Task 11).

INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at)
SELECT 'NEGOTIATION_THREAD', id, 'AUTO_CANCELLED_MIGRATION_V208', traveler_id,
       jsonb_build_object('previousStatus', 'AWAITING_TRIP', 'reason', 'trip-now-required-at-offer'),
       NOW()
FROM negotiation_threads
WHERE status = 'AWAITING_TRIP';

UPDATE negotiation_threads
SET status = 'CANCELLED', updated_at = NOW()
WHERE status = 'AWAITING_TRIP';
