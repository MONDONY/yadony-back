-- Plan de réduction du bruit des notifications : RECIPIENT_PARCEL_CANCELLED pouvait partir
-- deux fois pour le même colis (relance de remboursement par l'admin sur un colis déjà
-- annulé, absence de l'expéditeur confirmée après l'annulation du colis). Le premier envoi
-- pose cette date ; les suivants la trouvent et ne partent pas.
ALTER TABLE bid_recipient_links
    ADD COLUMN cancel_notified_at TIMESTAMP WITH TIME ZONE;
