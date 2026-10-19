-- Plan de réduction du bruit des notifications, étape 4b : le réglage « Offres »
-- (push_activity_bids) mélangeait trois rôles. Il devient « Mes envois » (expéditeur),
-- et deux interrupteurs le rejoignent : « Mes trajets » (voyageur) et « Colis pour moi »
-- (destinataire d'un colis envoyé par un autre).
--
-- Valeur initiale reprise de push_activity_bids : qui avait coupé « Offres » ne doit pas
-- se remettre à recevoir ces notifications sans l'avoir choisi.
ALTER TABLE user_notification_preferences
    ADD COLUMN push_my_trips          BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN push_recipient_parcels BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE user_notification_preferences
   SET push_my_trips = push_activity_bids,
       push_recipient_parcels = push_activity_bids
 WHERE push_activity_bids = FALSE;
