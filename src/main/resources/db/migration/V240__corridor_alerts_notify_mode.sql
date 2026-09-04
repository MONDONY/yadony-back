-- Fréquence des notifications d'une alerte : INSTANT (push dès qu'un élément
-- matche, digest en filet), DAILY (digest de 9 h seulement), MUTED (rien, le
-- compteur de nouveautés continue). Les alertes existantes gardent le
-- comportement d'avant : INSTANT.
ALTER TABLE corridor_alerts
    ADD COLUMN notify_mode VARCHAR(16) NOT NULL DEFAULT 'INSTANT';
