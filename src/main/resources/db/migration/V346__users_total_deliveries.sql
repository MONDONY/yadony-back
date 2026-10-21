-- V346 : compteur d'envois réussis du voyageur (classement des annonces).
-- users.total_deliveries = nombre de colis (bids COMPLETED) livrés ; bids.delivery_counted
-- rend l'incrément idempotent (TravelerStatsListener).
ALTER TABLE users
    ADD COLUMN total_deliveries INTEGER NOT NULL DEFAULT 0;

ALTER TABLE bids
    ADD COLUMN delivery_counted BOOLEAN NOT NULL DEFAULT FALSE;

-- Rattrapage historique : chaque bid COMPLETED non supprimé est marqué compté,
-- et le voyageur de l'annonce reçoit le total de ses colis livrés.
UPDATE bids
SET delivery_counted = TRUE
WHERE status = 'COMPLETED'
  AND deleted_at IS NULL;

UPDATE users u
SET total_deliveries = c.n
FROM (
    SELECT a.traveler_id AS user_id, COUNT(*) AS n
    FROM bids b
    JOIN announcements a ON a.id = b.announcement_id
    WHERE b.delivery_counted = TRUE
    GROUP BY a.traveler_id
) c
WHERE u.id = c.user_id;
