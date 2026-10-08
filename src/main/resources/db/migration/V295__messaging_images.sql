-- FLUTTER-B4 : photos dans la messagerie.
--
-- Le message lui-même vit dans Firestore (écrit par le back, jamais par le client). Cette
-- table est la source de vérité côté serveur pour les objets R2 qu'il porte : l'endpoint de
-- lecture y prend les clés (jamais dans le document Firestore, modifiable par un client), et
-- la purge y trouve ce qu'elle doit effacer sans parcourir Firestore.
--
-- Pas de clé étrangère vers conversations : la purge définitive d'une conversation (les deux
-- parties l'ont supprimée) efface la ligne conversation, alors que celles-ci doivent rester
-- pour tracer la purge (purged_at).
CREATE TABLE messaging_images (
    id                   UUID PRIMARY KEY,
    conversation_id      UUID         NOT NULL,
    bid_id               UUID         NOT NULL,
    firestore_message_id VARCHAR(40)  NOT NULL,
    sender_id            UUID         NOT NULL,
    image_key            VARCHAR(255) NOT NULL,
    thumb_key            VARCHAR(255) NOT NULL,
    purged_at            TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    deleted_at           TIMESTAMP,
    CONSTRAINT uq_messaging_images_message UNIQUE (conversation_id, firestore_message_id)
);

-- La purge ne lit que les photos encore présentes, regroupées par bid.
CREATE INDEX idx_messaging_images_live_bid ON messaging_images (bid_id)
    WHERE purged_at IS NULL AND deleted_at IS NULL;
