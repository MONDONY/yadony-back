-- Plan de réduction du bruit des notifications, étape 5 : des e-mails doublent ou remplacent
-- certains push. Les reçus, les décisions et les démarches du compte partent toujours ; deux
-- familles se coupent : les récapitulatifs d'un colis, les alertes et conseils.
--
-- Défaut TRUE : ces e-mails sont nouveaux, ils partent pour tout le monde jusqu'à ce que
-- l'utilisateur les coupe dans Paramètres › Notifications.
ALTER TABLE user_notification_preferences
    ADD COLUMN email_recaps      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN email_alerts_tips BOOLEAN NOT NULL DEFAULT TRUE;
