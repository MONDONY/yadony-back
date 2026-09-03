-- Refonte du sheet de notifications (2026-09) : le serveur devient responsable
-- de la forme. La notification porte sa catégorie, sa clé d'agrégation, sa
-- destination et, pour les annonces seulement, son texte complet.
--
-- Colonnes ajoutées nullables puis remplies : ajouter une colonne NOT NULL d'un
-- coup casserait les tests de migration, qui insèrent sans elle. Le NOT NULL
-- de category viendra dans une migration suivante, une fois le backfill
-- constaté en production. La table Java de référence est
-- NotificationCategory / NotificationGroupKey / NotificationDeeplink : toute
-- modification là-bas se reporte ici pour les lignes déjà en base.

ALTER TABLE notifications
    ADD COLUMN category  VARCHAR(20),
    ADD COLUMN group_key VARCHAR(120),
    ADD COLUMN deeplink  VARCHAR(255),
    ADD COLUMN full_body TEXT;

-- 1. Catégorie, déduite du type. Tout ce qui n'est pas listé reste dans le feed
--    (COLIS) : le pire serait qu'un type disparaisse dans la boîte des annonces.
UPDATE notifications SET category = CASE
    WHEN type IN ('ADMIN_BROADCAST', 'SYSTEM', 'ADMIN_WARNING', 'MESSAGING_MUTED')
        THEN 'ANNONCE'
    WHEN type IN ('PAYMENT_RELEASED', 'MM_PAYMENT_PENDING', 'MOBILE_MONEY_PAYMENT_CONFIRMED',
                  'KYC_VERIFIED', 'KYC_ACTION_REQUIRED', 'KYC_RESET',
                  'STRIPE_ONBOARDING_INCOMPLETE', 'CARD_EXPIRING',
                  'negotiation_awaiting_payment', 'negotiation_commission_pending',
                  'negotiation_commission_declined', 'negotiation_commission_expired')
        THEN 'PAIEMENTS'
    WHEN type IN ('TRIP_IN_PROGRESS', 'CORRIDOR_ALERT', 'TRAVELER_NEW_ANNOUNCEMENT',
                  'PACKAGE_MATCH', 'TRAVELER_INVITE', 'ANNOUNCEMENT_REMOVED',
                  'automation_capacity_free', 'automation_loyal_sender',
                  'negotiation', 'negotiation_started', 'negotiation_counter',
                  'negotiation_expired', 'negotiation_awaiting_trip', 'negotiation_trip_changed',
                  'request_accepted', 'request_expired')
        THEN 'TRAJETS'
    ELSE 'COLIS'
END
WHERE category IS NULL;

-- 2. Une annonce garde son texte complet : le corps court est recalculé par
--    l'app tant que la ligne existe, mais le texte d'origine ne doit pas se perdre.
UPDATE notifications SET full_body = body
WHERE category = 'ANNONCE' AND full_body IS NULL AND body IS NOT NULL;

-- 3. Clé d'agrégation, seulement pour ce qui se répète légitimement. Les autres
--    lignes gardent NULL et répondent « notif:{id} » côté Java.
UPDATE notifications SET group_key = 'bid:announcement:' || (data->>'announcementId')
WHERE type IN ('BID_CREATED', 'bid_negotiation_message')
  AND data->>'announcementId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET group_key = 'request:thread:' || (data->>'threadId')
WHERE type IN ('negotiation', 'negotiation_started', 'negotiation_counter')
  AND data->>'threadId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET group_key = 'alert:' || (data->>'alertId')
WHERE type = 'CORRIDOR_ALERT'
  AND data->>'alertId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET group_key = 'alert:corridor:' || btrim(data->>'corridor')
WHERE type = 'CORRIDOR_ALERT' AND group_key IS NULL
  AND coalesce(btrim(data->>'corridor'), '') <> '';

UPDATE notifications SET group_key = 'match:announcement:' || (data->>'announcementId')
WHERE type = 'PACKAGE_MATCH'
  AND data->>'announcementId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET group_key = 'follow:traveler:' || (data->>'travelerId')
WHERE type = 'TRAVELER_NEW_ANNOUNCEMENT'
  AND data->>'travelerId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

-- 4. Deeplink, miroir de notification_route_resolver.dart. Les cas les plus
--    fréquents seulement ; une ligne sans deeplink et sans être une annonce est
--    routée par l'app comme aujourd'hui, à partir du type.
UPDATE notifications SET deeplink = 'yadony://bids/' || (data->>'bidId')
WHERE type IN ('BID_ACCEPTED', 'DELIVERY_CONFIRMED', 'PAYMENT_RELEASED', 'DISPUTE_OPENED', 'PARCEL_REFUSED',
               'BID_EXPIRED', 'CONFIRMATION_CODE_READY', 'DELIVERY_NOSHOW_REPORTED', 'MM_PAYMENT_PENDING',
               'HANDOVER_REMINDER_H2', 'MOBILE_MONEY_PAYMENT_CONFIRMED', 'PARCEL_RETURNED',
               'RETURN_DEADLINE_WARNING', 'RETURN_DEADLINE_EXPIRED', 'automation_last_minute')
  AND data->>'bidId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://announcements/' || (data->>'announcementId') || '/bids'
WHERE type = 'BID_CREATED'
  AND data->>'announcementId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://cancellations/' || (data->>'cancellationId') || '/rematch'
WHERE type IN ('BID_REJECTED', 'TRIP_CANCELLED')
  AND data->>'cancellationId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://bids/' || (data->>'bidId')
WHERE type IN ('BID_REJECTED', 'TRIP_CANCELLED') AND deeplink IS NULL
  AND data->>'bidId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://profile/shipments/history'
WHERE type = 'TRIP_CANCELLED' AND deeplink IS NULL;

UPDATE notifications SET deeplink = 'yadony://negotiations/' || (data->>'threadId')
WHERE (type LIKE 'negotiation%' OR type = 'request_accepted')
  AND data->>'threadId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://package-requests/' || (data->>'packageRequestId')
WHERE type = 'request_expired'
  AND data->>'packageRequestId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://package-requests/' || (data->>'requestId') || '/public'
WHERE type IN ('TRAVELER_INVITE', 'PACKAGE_MATCH')
  AND data->>'requestId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://traveler/' || (data->>'announcementId')
WHERE type IN ('TRAVELER_NEW_ANNOUNCEMENT', 'CORRIDOR_ALERT', 'automation_loyal_sender')
  AND data->>'announcementId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = 'yadony://announcements/' || (data->>'announcementId') || '/trip'
WHERE type IN ('TRIP_IN_PROGRESS', 'automation_capacity_free')
  AND data->>'announcementId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE notifications SET deeplink = CASE type
    WHEN 'KYC_VERIFIED'                 THEN 'yadony://kyc/status'
    WHEN 'KYC_ACTION_REQUIRED'          THEN 'yadony://kyc/verify'
    WHEN 'DISPUTE_UPDATED'              THEN 'yadony://disputes'
    WHEN 'DISPUTE_RESOLVED'             THEN 'yadony://disputes'
    WHEN 'ACCOUNT_SUSPENDED'            THEN 'yadony://account/disabled'
    WHEN 'STRIPE_ONBOARDING_INCOMPLETE' THEN 'yadony://connect/onboarding/intro'
    WHEN 'CARD_EXPIRING'                THEN 'yadony://payments/commission-method'
END
WHERE type IN ('KYC_VERIFIED', 'KYC_ACTION_REQUIRED', 'DISPUTE_UPDATED', 'DISPUTE_RESOLVED',
               'ACCOUNT_SUSPENDED', 'STRIPE_ONBOARDING_INCOMPLETE', 'CARD_EXPIRING');

-- 5. Les messages sortent du feed : la messagerie porte déjà son badge et sa
--    liste. Les lignes existantes sont soft-deletées, jamais supprimées.
UPDATE notifications SET deleted_at = now()
WHERE type = 'NEW_MESSAGE' AND deleted_at IS NULL;

-- 6. La boîte « Annonces yadony » lit par catégorie.
CREATE INDEX idx_notifications_user_category
    ON notifications (user_id, category, created_at DESC)
    WHERE deleted_at IS NULL;
