-- Modals : fréquence « à chaque fois » (EVERY_TIME).
--
-- Le modal s'ouvre à chaque occurrence d'un moment d'action, sans plafond d'affichages. La colonne
-- frequency (VARCHAR(20)) accepte déjà la valeur : seule la contrainte CHECK de V374 la refuse.
-- Additive : les campagnes existantes (ONCE, UP_TO_N, EVERY_OPEN, EVERY_N_DAYS) restent valides.
ALTER TABLE video_modal_campaigns DROP CONSTRAINT video_modal_campaigns_frequency_check;
ALTER TABLE video_modal_campaigns
    ADD CONSTRAINT video_modal_campaigns_frequency_check
        CHECK (frequency IN ('ONCE', 'UP_TO_N', 'EVERY_OPEN', 'EVERY_N_DAYS', 'EVERY_TIME'));
