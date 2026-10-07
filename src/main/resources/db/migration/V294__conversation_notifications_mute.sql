-- FLUTTER-CM : sourdine d'une conversation, par participant et invisible pour l'autre.
-- Calquée sur l'archivage par participant (V100) : une colonne par côté, non nulle quand
-- ce participant a coupé les push des nouveaux messages de ce fil. Les non-lus continuent
-- d'être crédités et les appels ne sont pas concernés.
--
-- Aucun index : la colonne n'est lue que sur la ligne déjà chargée (notification d'un
-- message, rendu d'une conversation), jamais en filtre de liste.

ALTER TABLE conversations ADD COLUMN IF NOT EXISTS sender_notifications_muted_at   TIMESTAMPTZ NULL;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS traveler_notifications_muted_at TIMESTAMPTZ NULL;
