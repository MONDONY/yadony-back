-- V249: bids mobile money (rail pawaPay).
-- 1. payment_method accepte MOBILE_MONEY (WAVE/ORANGE_MONEY restent pour l'historique).
ALTER TABLE public.bids DROP CONSTRAINT IF EXISTS bids_payment_method_check;
ALTER TABLE public.bids
    ADD CONSTRAINT bids_payment_method_check
    CHECK (payment_method IN ('STRIPE', 'CASH', 'WAVE', 'ORANGE_MONEY', 'MOBILE_MONEY'));

-- 2. Le numéro payeur est désormais chiffré (EncryptedStringConverter), qui s'applique à
--    TOUTE ligne non nulle, quel que soit payment_method. Colonne élargie pour porter un
--    chiffré (plus long qu'un MSISDN en clair) ; toute valeur legacy en clair est vidée
--    pour ne pas faire échouer le déchiffrement à la matérialisation de l'entité (pas
--    seulement à l'usage du bid : une simple lecture de la ligne, ex. « Mes colis » de
--    l'expéditeur, suffirait à faire échouer Hibernate). Non scopé à WAVE/ORANGE_MONEY :
--    aucun code n'écrit plus ce champ en clair (l'écrivain historique a disparu avec le
--    paquet supprimé à la tâche 1), vider n'importe quelle ligne existante ne perd donc
--    rien d'exploitable.
UPDATE public.bids SET mobile_money_phone = NULL, mobile_money_country_code = NULL
 WHERE mobile_money_phone IS NOT NULL;
ALTER TABLE public.bids ALTER COLUMN mobile_money_phone TYPE VARCHAR(255);
