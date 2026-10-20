-- YADONY-FLUTTER-PROD-1R : la date limite de dépôt (announcements.handover_deadline,
-- bids.handover_deadline) est stockée en UTC, comme tout LocalDateTime du projet (l'app envoie
-- toUtc().toIso8601String()). Deux écritures côté serveur la posaient pourtant en HEURE MURALE
-- de la ville de départ, sans conversion :
--   1. les trajets dédiés (négociation) : handover_deadline = departure_date + departure_time
--      (23:59 sans heure), NegotiationService.buildDedicatedTripAnnouncement ;
--   2. les occurrences de trajets récurrents : handover_deadline = departure_date +
--      departure_time (12:00 sans heure) - handover_lead_days jours, ou le départ lui-même
--      quand la limite aurait déjà été passée (TripRecurrenceService.buildRequest).
-- Le code écrit désormais en UTC ; cette migration recale l'existant encore actif.
--
-- Identification fiable : on ne convertit QUE les trajets
--   - dédiés (linked_package_request_id IS NOT NULL) ou issus d'une récurrence
--     (source_recurrence_id IS NOT NULL) ;
--   - dont la date limite vaut EXACTEMENT la valeur que le serveur y écrivait (signature
--     ci-dessous) : une limite modifiée depuis l'app (déjà en UTC) ne la reproduit pas ;
--   - jamais reportés (reschedule_count = 0) : un report réécrit la limite depuis l'app, en UTC ;
--   - non terminés et non supprimés (DRAFT, ACTIVE, FULL, IN_PROGRESS ; deleted_at IS NULL).
-- Fuseau : la colonne timezone du trajet, repli Europe/Paris si elle n'est pas un fuseau connu
-- de PostgreSQL (même repli que TripTimezones.zoneOf côté Java).
--
-- Les colonnes sont en TIMESTAMP WITH TIME ZONE et Hibernate écrit le LocalDateTime comme une
-- heure UTC (hibernate.jdbc.time_zone = UTC). La valeur Java « L » se relit donc
-- (handover_deadline AT TIME ZONE 'UTC'), et la vraie limite est (L AT TIME ZONE fuseau du
-- trajet). Toutes les conversions sont explicites : rien ne dépend du fuseau de la session.
--
-- Les demandes (bids) copient la date limite du trajet à leur création : on recale aussi
-- celles qui portent encore l'ancienne valeur du trajet (rappel H-2, absence automatique).

CREATE TEMPORARY TABLE v344_deadline_fix ON COMMIT DROP AS
SELECT a.id                                                  AS announcement_id,
       a.handover_deadline                                   AS old_deadline,
       ((a.handover_deadline AT TIME ZONE 'UTC') AT TIME ZONE z.tz) AS new_deadline
FROM announcements a
LEFT JOIN trip_recurrences r ON r.id = a.source_recurrence_id
CROSS JOIN LATERAL (
    SELECT CASE
               WHEN EXISTS (SELECT 1 FROM pg_timezone_names t WHERE t.name = a.timezone)
                   THEN a.timezone
               ELSE 'Europe/Paris'
           END AS tz
) z
WHERE a.deleted_at IS NULL
  AND a.handover_deadline IS NOT NULL
  AND a.departure_date IS NOT NULL
  AND a.status IN ('DRAFT', 'ACTIVE', 'FULL', 'IN_PROGRESS')
  AND COALESCE(a.reschedule_count, 0) = 0
  AND (
        -- 1. trajet dédié : départ (ou 23:59) en heure murale
        (a.linked_package_request_id IS NOT NULL
         AND (a.handover_deadline AT TIME ZONE 'UTC')
             = a.departure_date + COALESCE(a.departure_time, TIME '23:59'))
     OR
        -- 2. occurrence de récurrence : départ (ou 12:00) moins le délai de remise, ou le
        --    départ lui-même (repli quand la limite aurait déjà été passée)
        (a.source_recurrence_id IS NOT NULL
         AND a.linked_package_request_id IS NULL
         AND r.id IS NOT NULL
         AND ((a.handover_deadline AT TIME ZONE 'UTC')
                  = a.departure_date + COALESCE(a.departure_time, TIME '12:00')
              OR (a.handover_deadline AT TIME ZONE 'UTC')
                  = a.departure_date + COALESCE(a.departure_time, TIME '12:00')
                    - make_interval(days => COALESCE(r.handover_lead_days, 0))))
  );

UPDATE bids b
SET handover_deadline = f.new_deadline
FROM v344_deadline_fix f
WHERE b.announcement_id = f.announcement_id
  AND b.deleted_at IS NULL
  AND b.handover_deadline = f.old_deadline;

UPDATE announcements a
SET handover_deadline = f.new_deadline
FROM v344_deadline_fix f
WHERE a.id = f.announcement_id;
