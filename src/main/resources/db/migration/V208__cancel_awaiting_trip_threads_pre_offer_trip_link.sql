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
WHERE status = 'AWAITING_TRIP' AND status <> 'CANCELLED';

-- Miroir de NegotiationService#reopenRequestWhenNoActiveNegotiation : ces threads
-- viennent d'être annulés, une package_request dont c'était le seul thread actif
-- (statut NEGOTIATING) doit rouvrir en OPEN, sinon elle reste bloquée en
-- "négociation en cours" à vie côté expéditeur, un état que le runtime ne
-- produit jamais.
UPDATE package_requests pr
SET status = 'OPEN', updated_at = NOW()
WHERE pr.status = 'NEGOTIATING'
  AND NOT EXISTS (
      SELECT 1 FROM negotiation_threads t
      WHERE t.package_request_id = pr.id
        AND t.status IN ('OPEN', 'AWAITING_TRIP', 'AWAITING_PAYMENT')
  );

-- Miroir de NegotiationService#softDeleteOrphanedDedicatedTrip : un trajet DÉDIÉ
-- (créé exclusivement pour une de ces demandes via createDedicatedTrip, avant
-- déploiement) devient orphelin une fois son thread annulé — sans ce nettoyage
-- il reste ACTIVE pour toujours, une entrée morte dans "Mes trajets".
INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at)
SELECT 'ANNOUNCEMENT', a.id, 'DEDICATED_TRIP_ORPHANED_MIGRATION_V208', a.traveler_id,
       jsonb_build_object('reason', 'thread-auto-cancelled-migration-v208'), NOW()
FROM announcements a
JOIN negotiation_threads t ON t.package_request_id = a.linked_package_request_id
WHERE a.linked_package_request_id IS NOT NULL
  AND a.deleted_at IS NULL
  AND t.status = 'CANCELLED';

UPDATE announcements a
SET deleted_at = NOW(), updated_at = NOW()
FROM negotiation_threads t
WHERE t.package_request_id = a.linked_package_request_id
  AND a.linked_package_request_id IS NOT NULL
  AND a.deleted_at IS NULL
  AND t.status = 'CANCELLED';
