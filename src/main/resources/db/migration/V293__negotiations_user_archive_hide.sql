-- V293 : archiver ou retirer une discussion de prix terminée (FLUTTER-EJ).
--
-- Même modèle que les conversations (V100, sender/traveler_archived_at) : chaque
-- participant range ou retire SA vue du fil, l'autre partie la garde intacte.
-- Retirer n'est jamais une suppression : le fil reste entier pour l'autre partie,
-- le back-office et l'audit, seule la liste du participant l'écarte.
-- Toutes les colonnes sont nullables : NULL = visible / non archivé.

-- Fils sur une demande d'envoi (package requests/).
ALTER TABLE negotiation_threads
    ADD COLUMN IF NOT EXISTS sender_archived_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS traveler_archived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS sender_hidden_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS traveler_hidden_at   TIMESTAMP WITH TIME ZONE;

-- Fils sur le prix d'un trajet (package matching/), portés par le bid lui-même (V216).
ALTER TABLE bids
    ADD COLUMN IF NOT EXISTS negotiation_sender_archived_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS negotiation_traveler_archived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS negotiation_sender_hidden_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS negotiation_traveler_hidden_at   TIMESTAMP WITH TIME ZONE;
