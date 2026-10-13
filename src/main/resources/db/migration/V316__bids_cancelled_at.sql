-- V316 : horodatage et statut quitté à l'annulation d'un bid (FLUTTER-P1).
--
-- L'appel in-app et le numéro de la contrepartie restent disponibles 48 h après
-- l'annulation d'une commande en cours (ACCEPTED, HANDED_OVER, IN_TRANSIT, ARRIVED).
-- Il faut pour cela savoir QUAND le bid est passé à CANCELLED et DEPUIS QUEL statut :
-- une demande annulée avant son acceptation n'ouvre aucun contact.
--
-- Colonnes posées par BidEntity.setStatus. Pas de reprise des annulations passées :
-- leur instant exact et leur statut d'origine sont inconnus, elles restent fermées.

ALTER TABLE bids ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE bids ADD COLUMN cancelled_from_status VARCHAR(30);
