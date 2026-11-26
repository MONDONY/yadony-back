-- Modals d'étape du colis : rôles sur le colis et fréquence « une fois par colis ».
--
-- Contraintes relues dans V366, V369, V372, V374, V375 et V386 :
--  * les déclencheurs (PARCEL_ACCEPTED, PARCEL_HANDED_OVER, PARCEL_IN_TRANSIT, PARCEL_ARRIVED,
--    PARCEL_DELIVERED) vivent dans la colonne JSON « triggers » (TEXT, sans CHECK) : rien à toucher ;
--  * les écrans des boutons (PARCEL_DETAIL, PARCEL_QR, PARCEL_SEND_CODE, PARCEL_TRACKING) vivent dans
--    les colonnes JSON « primary_action » et « secondary_action » (TEXT, sans CHECK) : rien à toucher ;
--  * la fréquence (VARCHAR(20), ONCE_PER_PARCEL tient en 15 caractères) est bornée par le CHECK
--    video_modal_campaigns_frequency_check (V374, refait par V386) : il est refait ici avec la nouvelle valeur.
--
-- Additive : les campagnes existantes gardent leur fréquence et ont parcel_roles NULL (= tous les rôles).
-- Aucune colonne bid_id : le back ne connaît pas le colis, l'app compare le rôle et dédoublonne par colis.

-- ["SENDER","TRAVELER","RECIPIENT"] écrit par l'API (convertisseur JPA) ; NULL = tous les rôles.
ALTER TABLE video_modal_campaigns ADD COLUMN parcel_roles TEXT;

ALTER TABLE video_modal_campaigns DROP CONSTRAINT video_modal_campaigns_frequency_check;
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_frequency_check
        CHECK (frequency IN ('ONCE', 'UP_TO_N', 'EVERY_OPEN', 'EVERY_N_DAYS', 'EVERY_TIME', 'ONCE_PER_PARCEL'));
