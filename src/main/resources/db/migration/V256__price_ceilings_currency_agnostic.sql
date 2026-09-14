-- V256 : les plafonds SQL des prix de colis et de négociation deviennent indépendants
-- de la devise.
--
-- Contexte : V57, V58 et V59 datent de l'époque « tout en euros » et bornaient
-- target_price_eur, current_price_eur et proposed_price_eur à 500. Depuis le passage
-- multidevise, ces colonnes portent le montant dans la devise de la demande (colonne
-- currency) : un budget de 3 000 F CFA (2 857,14 nets de commission) violait
-- chk_pkg_req_target_price à l'insertion, et toute négociation en XOF au-dessus de
-- 500 F CFA aurait violé chk_neg_thread_price ou chk_neg_msg_price. Le 422 Java
-- (plafond 560 EUR, retiré par #284) masquait ce refus SQL jusqu'ici.
--
-- La vraie borne par devise vit dans CurrencyBounds (PackageRequestService,
-- NegotiationService). La base ne garde que le signe et un garde-fou large, le même
-- que celui du DTO (1 000 000), qui couvre le maximum XOF/XAF de 560 EUR (~367 336).

ALTER TABLE package_requests
  DROP CONSTRAINT chk_pkg_req_target_price,
  ADD CONSTRAINT chk_pkg_req_target_price
    CHECK (target_price_eur IS NULL OR target_price_eur BETWEEN 0 AND 1000000);

ALTER TABLE negotiation_threads
  DROP CONSTRAINT chk_neg_thread_price,
  ADD CONSTRAINT chk_neg_thread_price
    CHECK (current_price_eur > 0 AND current_price_eur <= 1000000);

ALTER TABLE negotiation_messages
  DROP CONSTRAINT chk_neg_msg_price,
  ADD CONSTRAINT chk_neg_msg_price
    CHECK (proposed_price_eur IS NULL OR (proposed_price_eur > 0 AND proposed_price_eur <= 1000000));
