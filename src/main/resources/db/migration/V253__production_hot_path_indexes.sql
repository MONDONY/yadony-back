-- Index composites partiels pour les listes bornées que l'application mobile tire
-- le plus souvent (reprise de la PR #171, colonnes revalidées contre le schéma courant :
-- bids.deleted_at / deleted_by_traveler (V16) / deleted_by_sender (V14),
-- conversations.sender_deleted_at / traveler_deleted_at (V33), sender_archived_at /
-- traveler_archived_at (V100)).
--
-- Un index partiel n'est retenu par le planificateur que si le WHERE de la requête
-- IMPLIQUE son prédicat. Les prédicats ci-dessous sont donc calqués sur les requêtes
-- réelles, pas sur l'intention :
--
--   * bids : toutes les requêtes JPA portent `deleted_at IS NULL` (@Where de BidEntity),
--     mais `deleted_by_traveler` / `deleted_by_sender` sont filtrés en mémoire par
--     BidService (getBidsForAnnouncement, getMyBids). Un prédicat sur ces drapeaux (PR #171)
--     rendait l'index inutilisable par ces deux lectures ; on ne garde que `deleted_at IS
--     NULL`, ce qui sert aussi countVisibleByAnnouncementId(s) qui, lui, porte le drapeau.
--
--   * conversations : findByParticipant / findArchivedByParticipant filtrent sur les
--     colonnes PAR PARTIE (sender_*_at / traveler_*_at). Les index de V31 sont conditionnés
--     à `deleted_at IS NULL`, colonne que ces requêtes ne mentionnent plus depuis V33 : ils
--     ne leur servent pas. Quatre index, un par (partie, actif|archivé).
--
-- Pas de CONCURRENTLY : Flyway exécute la migration dans une transaction. Les tables sont
-- petites, le verrou SHARE le temps de la construction est négligeable.

-- Demandes reçues sur un trajet (GET /announcements/{id}/bids, compteurs des cartes trajet).
CREATE INDEX IF NOT EXISTS idx_bids_announcement_active_created
    ON bids (announcement_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Colis de l'expéditeur (GET /bids/me).
CREATE INDEX IF NOT EXISTS idx_bids_sender_active_created
    ON bids (sender_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Fils actifs (GET /conversations), côté expéditeur puis côté voyageur.
CREATE INDEX IF NOT EXISTS idx_conversations_sender_active_updated
    ON conversations (sender_id, updated_at DESC)
    WHERE sender_deleted_at IS NULL AND sender_archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_traveler_active_updated
    ON conversations (traveler_id, updated_at DESC)
    WHERE traveler_deleted_at IS NULL AND traveler_archived_at IS NULL;

-- Fils archivés (GET /conversations/archived), côté expéditeur puis côté voyageur.
CREATE INDEX IF NOT EXISTS idx_conversations_sender_archived_updated
    ON conversations (sender_id, updated_at DESC)
    WHERE sender_deleted_at IS NULL AND sender_archived_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_traveler_archived_updated
    ON conversations (traveler_id, updated_at DESC)
    WHERE traveler_deleted_at IS NULL AND traveler_archived_at IS NOT NULL;
