-- FLUTTER-GB : trois nouvelles familles de notifications deviennent réglables.
--
-- Jusqu'ici, un type absent de NotificationPrefsService.TYPE_TO_PREF partait toujours,
-- quoi que l'utilisateur ait choisi. Les appels manqués, les automatisations du voyageur
-- et les rappels/conseils (premiers pas, « Bon voyage ») reçoivent chacun leur interrupteur.
--
-- Défaut TRUE : ces notifications partaient déjà à tout le monde, personne ne doit
-- cesser de les recevoir sans l'avoir choisi.
ALTER TABLE user_notification_preferences
    ADD COLUMN push_missed_calls         BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN push_traveler_automations BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN push_reminders_tips       BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN user_notification_preferences.push_trip_reminder IS
    'Historique, plus lu par aucun type depuis V305 : TRIP_IN_PROGRESS suit push_reminders_tips. '
    'Conservé dans le contrat GET/PUT pour les anciennes versions de l''application.';
