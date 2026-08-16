-- Compteur de vues de la page publique de partage d'une annonce.
--
-- Sert l'attribution : une affiche générée depuis l'app est postée par le
-- voyageur sur ses propres canaux (Facebook, WhatsApp, TikTok) et pointe vers
-- /public/annonce/{id}. Ce compteur mesure le trafic réellement ramené par
-- l'affiche, indépendamment des installations.
--
-- Colonne volontairement NULLABLE : une colonne NOT NULL ajoutée ici casse les
-- tests de migration sur H2, où le DDL est généré depuis JPA sans reprendre le
-- DEFAULT de Flyway. Toutes les lectures passent par COALESCE.
ALTER TABLE announcements
    ADD COLUMN IF NOT EXISTS share_view_count BIGINT DEFAULT 0;

COMMENT ON COLUMN announcements.share_view_count IS
    'Nombre de consultations de la page publique de partage (affiche). Attribution du trafic apporte par le voyageur.';
