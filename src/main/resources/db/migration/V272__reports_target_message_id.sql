-- V272 : un signalement de message sait enfin quel message il vise.
--
-- reports.target_id est un UUID ; les messages vivent dans Firestore sous un identifiant
-- chaîne (conversations/{firestoreConversationId}/messages/{messageId}). Pour une cible
-- MESSAGE, target_id désigne désormais la conversation (conversations.id) et cette colonne
-- l'identifiant Firestore du message. NULL pour les autres cibles et pour les signalements
-- de message antérieurs (leur message reste introuvable : seules les actions « traité » et
-- « rejeté » leur sont proposées).
--
-- Pas de CHECK sur action_taken à recréer : la colonne (V164) est un VARCHAR(40) libre.

ALTER TABLE reports ADD COLUMN IF NOT EXISTS target_message_id VARCHAR(128);
