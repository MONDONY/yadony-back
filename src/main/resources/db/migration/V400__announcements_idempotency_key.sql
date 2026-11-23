-- Création de trajet idempotente : l'en-tête Idempotency-Key de POST /announcements et de
-- POST /announcements/trips est gardé avec le trajet créé (première étape seulement pour un
-- voyage à étapes). Quand la réponse se perd (réseau mobile, délai dépassé, 502) et que l'app
-- renvoie le formulaire avec la même clé, le serveur relit le trajet au lieu d'en créer un second.
--
-- L'index unique partiel est la vraie garantie : deux requêtes simultanées de même clé ne
-- peuvent pas insérer deux trajets pour le même voyageur, la perdante bute sur l'index et relit
-- celui de la gagnante. La clé est propre au voyageur : (traveler_id, idempotency_key). Un trajet
-- supprimé (soft delete) libère sa clé.
--
-- Additive : NULL pour tous les trajets existants et pour les anciennes versions de l'app, qui
-- n'envoient pas de clé ; ces lignes restent hors de l'index. Pas de CONCURRENTLY (Flyway exécute
-- la migration dans une transaction) : la colonne est vide, l'index aussi, la table est petite.
ALTER TABLE announcements ADD COLUMN idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX uq_announcements_traveler_idempotency_key
    ON announcements (traveler_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND deleted_at IS NULL;
