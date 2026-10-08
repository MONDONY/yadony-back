-- V298 : compteur d'annulations expéditeur après acceptation (FLUTTER-E0/E6).
-- Annulation d'un colis par l'expéditeur (BidService.cancelBid) alors que le voyageur
-- l'avait déjà accepté. Les annulations après remise et les absences confirmées restent
-- dans sender_handover_incident_count (V138) : le profil public affiche la somme.
ALTER TABLE users
    ADD COLUMN sender_cancellation_count INTEGER NOT NULL DEFAULT 0;

-- Rattrapage historique depuis audit_log (lecture seule, la table est immuable).
-- cancelBid n'écrit pas de ligne dans cancellations : la trace est l'audit BID_CANCELLED
-- (payload.actor = SENDER). L'acceptation préalable se lit dans une trace d'acceptation
-- antérieure sur le même bid, ou dans une commission espèces prélevée sur ce bid
-- (commission_status renseigné, posé uniquement à l'acceptation).
WITH sender_cancellations AS (
    SELECT b.sender_id AS user_id, COUNT(DISTINCT a.entity_id) AS n
    FROM audit_log a
    JOIN bids b ON b.id = a.entity_id
    WHERE a.entity_type = 'BID'
      AND a.action = 'BID_CANCELLED'
      AND a.payload ->> 'actor' = 'SENDER'
      AND (b.commission_status IS NOT NULL
           OR EXISTS (SELECT 1
                      FROM audit_log p
                      WHERE p.entity_id = a.entity_id
                        AND p.created_at < a.created_at
                        AND p.action IN ('BID_ACCEPTED', 'MM_BID_ACCEPTED_AWAITING_PAYMENT',
                                         'CREATED_FROM_THREAD', 'PRESENCE_CONFIRMED',
                                         'COMMISSION_CHARGED_WALLET')))
    GROUP BY b.sender_id
)
UPDATE users u
SET sender_cancellation_count = s.n
FROM sender_cancellations s
WHERE u.id = s.user_id;
