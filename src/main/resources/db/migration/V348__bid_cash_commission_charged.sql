-- V348 : commission espèces réellement prélevée au voyageur sur un colis (portefeuille ou carte), dans la devise du
-- colis. Elle diffère du brut − net affiché dès qu'un bon de parrainage du voyageur la divise, qu'un plancher
-- (minimum-amount) ou un code promo s'applique sur un colis sans grille. Lue par la ventilation de l'argent d'un
-- colis modifié (cashCommissionPaid). NULL pour les colis antérieurs : la ventilation retombe alors sur brut − net.
ALTER TABLE bids ADD COLUMN cash_commission_charged_amount NUMERIC(14,2) NULL;
