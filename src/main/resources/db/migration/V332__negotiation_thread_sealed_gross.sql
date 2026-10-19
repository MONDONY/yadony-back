-- Total réellement payé par l'expéditeur, frais compris, figé au scellement d'un fil de demande
-- d'envoi à grille de commission (carte, mobile money : brut prélevé ; espèces : net + commission
-- prélevée au voyageur). Un fil scellé n'est jamais re-tarifé à l'affichage : le code promo y est
-- déjà racheté et le bon de parrainage consommé, les relire ferait annoncer un total supérieur
-- à celui débité. Dans la devise du fil (francs entiers en XOF/XAF).
-- Facultative : nulle pour les fils non scellés et pour ceux sans grille (calcul hérité).

ALTER TABLE negotiation_threads ADD COLUMN sealed_gross_eur NUMERIC(10,2) NULL;

ALTER TABLE negotiation_threads
    ADD CONSTRAINT chk_negotiation_threads_sealed_gross_positive
        CHECK (sealed_gross_eur IS NULL OR sealed_gross_eur > 0);
