-- Statut terminal d un fil de negociation eteint sans accord.
--
-- bids.status est VARCHAR(20) via EnumType.STRING et n a AUCUNE contrainte
-- CHECK : NEGOTIATION_CLOSED (18 caracteres) ne demande aucune modification
-- DDL. Meme gabarit que V71/V72/V73/V216.
--
-- Pourquoi un statut de plus plutot que REJECTED / CANCELLED recycles : un fil
-- clos n a jamais ete une reservation. Sous un statut de colis, il reapparait
-- dans « Mes envois », dans la liste voyageur du trajet, et dans le
-- denominateur du taux d acceptation du voyageur — qu un refus de PRIX par
-- l expediteur suffisait alors a degrader.
COMMENT ON COLUMN bids.status IS
  'BidStatus: PENDING | AWAITING_PAYMENT | PAYMENT_ESCROWED | ACCEPTED | HANDED_OVER | IN_TRANSIT | ARRIVED | REJECTED | CANCELLED | COMPLETED | NO_SHOW | PARCEL_REFUSED | EXPIRED | NEGOTIATING | NEGOTIATION_CLOSED';
