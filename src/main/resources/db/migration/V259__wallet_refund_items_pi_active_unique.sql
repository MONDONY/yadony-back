-- V259 : un PaymentIntent peut porter plusieurs items de remboursement dans le temps,
-- mais un seul item actif à la fois.
--
-- L'index unique COMPLET de V229 interdisait tout second item sur un PaymentIntent. Le ticket
-- manuel enfant ouvert après un échec Stripe ne pouvait donc pas porter d'items : à sa
-- résolution, ADMIN_REFUND_OUT n'était apparié à aucune demande et retombait en LIFO, ce qui
-- déclarait consommée une recharge fraîche à la place de celle en échec (restée bloquée pour
-- toujours par son item FAILED). Il empêchait aussi de redemander le reliquat d'une recharge
-- déjà partiellement remboursée.
--
-- L'unicité ne vaut plus que pour les items PENDING ou PROCESSING : deux remboursements en vol
-- sur le même PaymentIntent restent impossibles, et les webhooks Stripe retrouvent toujours un
-- seul item PROCESSING par PaymentIntent.

DROP INDEX uq_wallet_refund_request_items_pi;

CREATE UNIQUE INDEX uq_wallet_refund_request_items_pi_active
    ON wallet_refund_request_items (payment_intent_id)
    WHERE status IN ('PENDING', 'PROCESSING');
