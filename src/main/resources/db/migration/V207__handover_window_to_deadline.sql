-- Fenêtre de remise (début + fin) → date limite de dépôt unique.
--
-- Les utilisateurs ne comprenaient pas « fenêtre de remise » : deux dates à
-- saisir, sans savoir qui remet quoi à qui. Le modèle devient une seule date
-- limite : le voyageur déclare jusqu'à quand il accepte de récupérer les colis.
--
-- handover_window_end porte déjà exactement cette sémantique (borne haute),
-- il est donc renommé plutôt que recréé : les annonces et offres existantes
-- gardent leur valeur, aucune reprise de données n'est nécessaire.
-- handover_window_start disparaît : il n'était plus qu'une borne basse
-- implicite (l'annonce est ouverte dès sa publication).

ALTER TABLE announcements RENAME COLUMN handover_window_end TO handover_deadline;
ALTER TABLE announcements DROP COLUMN handover_window_start;

ALTER TABLE bids RENAME COLUMN handover_window_end TO handover_deadline;
ALTER TABLE bids DROP COLUMN handover_window_start;
