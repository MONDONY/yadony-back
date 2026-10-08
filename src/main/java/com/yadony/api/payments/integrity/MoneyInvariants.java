package com.yadony.api.payments.integrity;

import com.yadony.api.payments.integrity.MoneyInvariant.Severity;

import java.util.List;

/**
 * Les 17 règles de cohérence de l'argent vérifiées par {@link MoneyIntegrityMonitor}. Écrites
 * le 05/10 contre le schéma réel ; {@code MoneyIntegrityMonitorIT} les exécute toutes sur
 * Postgres embarqué avec les migrations, une colonne renommée y casse avant la production.
 *
 * <p>Familles : wallet (01-03, 14, 15), séquestre (04-06, 08), montants et devises (07, 17),
 * doublons et commission (09, 10), prestataires (11-13, 16).
 */
public final class MoneyInvariants {

    private MoneyInvariants() {
    }

    public static final List<MoneyInvariant> ALL = List.of(
            new MoneyInvariant("INV-01", "Solde du wallet égal à la somme de ses mouvements", Severity.CRITIQUE, """
                    WITH ledger AS (
                        SELECT user_id, currency, SUM(amount) AS ledger_sum, COUNT(*) AS nb_tx, MAX(created_at) AS last_tx_at
                        FROM wallet_transactions
                        GROUP BY user_id, currency
                    )
                    SELECT wa.id AS wallet_id,
                           COALESCE(wa.user_id, l.user_id) AS user_id,
                           COALESCE(wa.currency, l.currency) AS currency,
                           wa.balance,
                           COALESCE(l.ledger_sum, 0) AS ledger_sum,
                           COALESCE(wa.balance, 0) - COALESCE(l.ledger_sum, 0) AS drift,
                           l.nb_tx, l.last_tx_at, wa.deleted_at
                    FROM wallet_accounts wa
                    FULL OUTER JOIN ledger l ON l.user_id = wa.user_id AND l.currency = wa.currency
                    WHERE COALESCE(wa.balance, 0) <> COALESCE(l.ledger_sum, 0)
                    """),
            new MoneyInvariant("INV-02", "Chaîne des soldes continue (ancien solde + mouvement = nouveau solde)", Severity.HAUTE, """
                    SELECT id, user_id, currency, type, amount, balance_after, prev_balance_after, created_at
                    FROM (
                        SELECT t.id, t.user_id, t.currency, t.type, t.amount, t.balance_after, t.created_at,
                               LAG(t.balance_after) OVER (PARTITION BY t.user_id, t.currency ORDER BY t.created_at, t.id) AS prev_balance_after
                        FROM wallet_transactions t
                    ) x
                    WHERE COALESCE(prev_balance_after, 0) + amount <> balance_after
                    """),
            new MoneyInvariant("INV-03", "Aucun solde négatif, signe cohérent avec le type, ajustement admin signé", Severity.HAUTE, """
                    SELECT 'SOLDE_NEGATIF' AS anomaly, wa.id AS ref_id, wa.user_id, wa.currency, wa.balance AS value
                    FROM wallet_accounts wa WHERE wa.balance < 0
                    UNION ALL
                    SELECT 'BALANCE_AFTER_NEGATIF', t.id, t.user_id, t.currency, t.balance_after
                    FROM wallet_transactions t WHERE t.balance_after < 0
                    UNION ALL
                    SELECT 'SIGNE_INCOHERENT_AVEC_TYPE', t.id, t.user_id, t.currency, t.amount
                    FROM wallet_transactions t
                    WHERE (t.type IN ('TOP_UP','REFUND','REFERRAL_REWARD','ADMIN_CREDIT') AND t.amount <= 0)
                       OR (t.type IN ('BID_PAYMENT','COMMISSION_DEDUCTED','ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION','ADMIN_DEBIT') AND t.amount > 0)
                       OR (t.type IN ('BID_PAYMENT','ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION','ADMIN_DEBIT') AND t.amount = 0)
                    UNION ALL
                    SELECT 'AJUSTEMENT_ADMIN_SANS_AUTEUR', t.id, t.user_id, t.currency, t.amount
                    FROM wallet_transactions t
                    WHERE t.type IN ('ADMIN_CREDIT','ADMIN_DEBIT') AND (t.admin_actor_id IS NULL OR t.admin_reason IS NULL)
                    """),
            new MoneyInvariant("INV-04", "Pas d'argent bloqué sur un colis annulé, refusé, expiré ou absent", Severity.HAUTE, """
                    SELECT p.id AS payment_id, p.rail, p.amount, p.currency, p.created_at, p.disputed, p.payout_held_at,
                           b.id AS bid_id, b.status AS bid_status, nt.id AS thread_id, nt.status AS thread_status
                    FROM payments p
                    LEFT JOIN LATERAL (
                        SELECT bb.id, bb.status, bb.updated_at
                        FROM bids bb
                        WHERE bb.id = p.bid_id
                           OR (p.bid_id IS NULL AND bb.linked_negotiation_thread_id = p.negotiation_thread_id)
                        ORDER BY bb.created_at DESC
                        LIMIT 1
                    ) b ON true
                    LEFT JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
                    WHERE p.deleted_at IS NULL
                      AND p.status = 'ESCROW'
                      AND (
                            (b.status IN ('CANCELLED','REJECTED','EXPIRED','NO_SHOW','PARCEL_REFUSED') AND b.updated_at < now() - interval '1 hour')
                         OR (b.id IS NULL AND nt.status IN ('REJECTED','AUTO_REJECTED','EXPIRED','CANCELLED') AND nt.updated_at < now() - interval '1 hour')
                      )
                    """),
            new MoneyInvariant("INV-05", "Argent versé au voyageur seulement si le colis est livré", Severity.CRITIQUE, """
                    SELECT p.id AS payment_id, p.rail, p.amount, p.commission_amount, p.currency, p.escrow_released_at,
                           b.id AS bid_id, b.status AS bid_status,
                           EXISTS (SELECT 1 FROM audit_log a
                                   WHERE a.entity_type = 'PAYMENT' AND a.entity_id = p.id AND a.action = 'ESCROW_FORCE_RELEASED') AS force_release_admin,
                           CASE
                               WHEN b.id IS NULL THEN 'CRITIQUE_SANS_BID'
                               WHEN b.status IN ('CANCELLED','REJECTED','EXPIRED','NO_SHOW','PARCEL_REFUSED') THEN 'CRITIQUE_BID_ECHOUE'
                               ELSE 'A_VERIFIER'
                           END AS gravite
                    FROM payments p
                    LEFT JOIN LATERAL (
                        SELECT bb.id, bb.status
                        FROM bids bb
                        WHERE bb.id = p.bid_id
                           OR (p.bid_id IS NULL AND bb.linked_negotiation_thread_id = p.negotiation_thread_id)
                        ORDER BY bb.created_at DESC
                        LIMIT 1
                    ) b ON true
                    WHERE p.deleted_at IS NULL
                      AND p.status = 'RELEASED'
                      AND (b.id IS NULL OR b.status <> 'COMPLETED')
                      -- FLUTTER-E2 : colis « non réclamé » payé au terme de la garde, ou partage admin.
                      AND NOT EXISTS (SELECT 1 FROM cancellations cu
                                      WHERE cu.bid_id = b.id AND cu.unclaimed_at IS NOT NULL)
                      AND NOT EXISTS (SELECT 1 FROM payment_splits ps
                                      WHERE ps.payment_id = p.id AND ps.deleted_at IS NULL)
                    """),
            new MoneyInvariant("INV-06", "Colis livré depuis plus de 2 h bien payé au voyageur", Severity.HAUTE, """
                    SELECT p.id AS payment_id, p.rail, p.amount, p.currency, p.disputed, p.payout_held_at,
                           b.id AS bid_id, COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at) AS delivered_at
                    FROM payments p
                    JOIN LATERAL (
                        SELECT bb.id, bb.status, bb.delivered_at, bb.updated_at
                        FROM bids bb
                        WHERE bb.id = p.bid_id
                           OR (p.bid_id IS NULL AND bb.linked_negotiation_thread_id = p.negotiation_thread_id)
                        ORDER BY bb.created_at DESC
                        LIMIT 1
                    ) b ON true
                    WHERE p.deleted_at IS NULL
                      AND p.status = 'ESCROW'
                      AND b.status = 'COMPLETED'
                      AND COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at) < now() - interval '2 hours'
                    """),
            new MoneyInvariant("INV-07", "Montants dans leurs bornes, remboursements confirmés, litiges marqués", Severity.CRITIQUE, """
                    SELECT * FROM (
                        SELECT p.id AS payment_id, p.rail, p.status, p.amount, p.commission_amount, p.refunded_amount,
                               p.currency, p.disputed, p.captured_at,
                               CASE
                                   WHEN p.refunded_amount < 0 OR p.refunded_amount > p.amount THEN 'REFUND_HORS_BORNES'
                                   WHEN p.commission_amount > p.amount THEN 'COMMISSION_SUPERIEURE_AU_MONTANT'
                                   WHEN p.status = 'RELEASED' AND p.refunded_amount > 0
                                        AND NOT EXISTS (SELECT 1 FROM payment_splits ps
                                                        WHERE ps.payment_id = p.id AND ps.deleted_at IS NULL)
                                        THEN 'REMBOURSE_APRES_VERSEMENT'
                                   WHEN p.status = 'ESCROW' AND p.refunded_amount > 0 THEN 'ESCROW_PARTIELLEMENT_REMBOURSE'
                                   WHEN p.status = 'RELEASED' AND p.escrow_released_at IS NULL THEN 'RELEASED_SANS_DATE'
                                   WHEN p.disputed AND p.status = 'RELEASED' THEN 'LITIGE_SUR_FONDS_VERSES'
                                   WHEN NOT p.disputed AND EXISTS (SELECT 1 FROM chargebacks c
                                                                   WHERE c.payment_id = p.id AND c.status = 'OPEN' AND c.deleted_at IS NULL)
                                        THEN 'CHARGEBACK_OUVERT_NON_MARQUE'
                                   WHEN p.rail = 'STRIPE' AND p.status = 'REFUNDED' AND p.captured_at IS NOT NULL
                                        AND COALESCE(p.refunded_amount, 0) < p.amount
                                        AND NOT EXISTS (SELECT 1 FROM payment_splits ps
                                                        WHERE ps.payment_id = p.id AND ps.deleted_at IS NULL)
                                        AND (SELECT max(a.created_at) FROM audit_log a
                                             WHERE a.entity_type = 'PAYMENT' AND a.entity_id = p.id) < now() - interval '1 day'
                                        THEN 'REFUND_STRIPE_NON_CONFIRME'
                               END AS anomaly
                        FROM payments p
                        WHERE p.deleted_at IS NULL
                    ) x
                    WHERE anomaly IS NOT NULL
                    """),
            new MoneyInvariant("INV-08", "Séquestre carte sain : autorisation non expirée, colis engagé avec des fonds", Severity.HAUTE, """
                    SELECT * FROM (
                        SELECT p.id AS payment_id, p.status, p.amount, p.currency, p.created_at, p.captured_at,
                               b.id AS bid_id, b.status AS bid_status, b.payment_method,
                               CASE
                                   WHEN p.status = 'ESCROW' AND p.captured_at IS NULL AND p.created_at < now() - interval '5 days'
                                        THEN 'AUTORISATION_CARTE_PROCHE_EXPIRATION'
                                   WHEN p.status = 'ESCROW' AND p.captured_at IS NULL
                                        AND b.status IN ('ACCEPTED','HANDED_OVER','IN_TRANSIT','ARRIVED','COMPLETED')
                                        AND b.updated_at < now() - interval '1 hour'
                                        THEN 'BID_ACCEPTE_NON_CAPTURE'
                                   WHEN p.status IN ('CANCELLED','FAILED')
                                        AND b.status IN ('ACCEPTED','HANDED_OVER','IN_TRANSIT','ARRIVED','COMPLETED')
                                        AND b.payment_method <> 'CASH'
                                        THEN 'BID_ENGAGE_SANS_FONDS'
                               END AS anomaly
                        FROM payments p
                        LEFT JOIN LATERAL (
                            SELECT bb.id, bb.status, bb.updated_at, bb.payment_method
                            FROM bids bb
                            WHERE bb.id = p.bid_id
                               OR (p.bid_id IS NULL AND bb.linked_negotiation_thread_id = p.negotiation_thread_id)
                            ORDER BY bb.created_at DESC
                            LIMIT 1
                        ) b ON true
                        WHERE p.deleted_at IS NULL
                          AND p.rail = 'STRIPE'
                          AND NOT p.legacy_destination_charge
                    ) x
                    WHERE anomaly IS NOT NULL
                    """),
            new MoneyInvariant("INV-09", "Aucun doublon de transaction, de recharge ou de commission", Severity.CRITIQUE, """
                    SELECT 'PAWAPAY_PROVIDER_TX' AS kind, o.provider_transaction_id::text AS ref, COUNT(*) AS n
                    FROM pawapay_operations o
                    WHERE o.provider_transaction_id IS NOT NULL
                    GROUP BY o.provider_transaction_id HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT 'PAYMENTS_STRIPE_CHARGE', p.stripe_charge_id::text, COUNT(*)
                    FROM payments p
                    WHERE p.stripe_charge_id IS NOT NULL
                    GROUP BY p.stripe_charge_id HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT 'TOPUP_MEME_PAYMENT_REF', t.payment_ref::text, COUNT(*)
                    FROM wallet_transactions t
                    WHERE t.type = 'TOP_UP' AND t.payment_ref IS NOT NULL
                    GROUP BY t.payment_ref HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT 'COMMISSION_WALLET_DOUBLE', t.bid_id::text || ':' || t.currency, COUNT(*)
                    FROM wallet_transactions t
                    WHERE t.type = 'COMMISSION_DEDUCTED' AND t.bid_id IS NOT NULL
                    GROUP BY t.bid_id, t.currency HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT 'COMMISSION_RECREDIT_DOUBLE', t.payment_ref || ':' || t.currency, COUNT(*)
                    FROM wallet_transactions t
                    WHERE t.type = 'REFUND' AND t.payment_ref LIKE 'refund-%'
                    GROUP BY t.payment_ref, t.currency HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT 'COMMISSION_PI_PARTAGE', b.commission_payment_intent_id::text, COUNT(*)
                    FROM bids b
                    WHERE b.commission_payment_intent_id IS NOT NULL
                    GROUP BY b.commission_payment_intent_id HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT 'PI_PAIEMENT_ET_RECHARGE', p.stripe_payment_intent_id::text, COUNT(*)
                    FROM payments p
                    JOIN wallet_transactions t ON t.type = 'TOP_UP' AND t.payment_ref = p.stripe_payment_intent_id
                    GROUP BY p.stripe_payment_intent_id
                    """),
            new MoneyInvariant("INV-10", "Commission cohérente avec le colis et le grand livre", Severity.HAUTE, """
                    SELECT * FROM (
                        SELECT b.id AS bid_id, b.status, b.payment_method, b.commission_status, b.commission_charged_via,
                               b.commission_payment_intent_id, b.commission_retry_count, b.updated_at,
                               CASE
                                   WHEN b.commission_status = 'CHARGED' AND b.commission_charged_via IS NULL
                                        THEN 'CHARGEE_SANS_CANAL'
                                   WHEN b.commission_status = 'CHARGED' AND b.commission_charged_via = 'WALLET'
                                        AND NOT EXISTS (SELECT 1 FROM wallet_transactions t
                                                        WHERE t.bid_id = b.id AND t.type = 'COMMISSION_DEDUCTED')
                                        THEN 'WALLET_SANS_LIGNE_LEDGER'
                                   WHEN b.commission_status = 'CHARGED' AND b.commission_charged_via = 'CARD'
                                        AND b.commission_payment_intent_id IS NULL
                                        THEN 'CARTE_SANS_PAYMENT_INTENT'
                                   WHEN b.commission_status = 'REFUNDED' AND b.commission_charged_via = 'WALLET'
                                        AND EXISTS (SELECT 1 FROM wallet_transactions t
                                                    WHERE t.bid_id = b.id AND t.type = 'COMMISSION_DEDUCTED' AND t.amount < 0)
                                        AND NOT EXISTS (SELECT 1 FROM wallet_transactions t
                                                        WHERE t.type = 'REFUND' AND t.payment_ref = 'refund-' || b.id::text)
                                        THEN 'REMBOURSEE_SANS_RECREDIT'
                                   WHEN b.commission_status IS DISTINCT FROM 'CHARGED' AND b.commission_status IS DISTINCT FROM 'REFUNDED'
                                        AND EXISTS (SELECT 1 FROM wallet_transactions t
                                                    WHERE t.bid_id = b.id AND t.type = 'COMMISSION_DEDUCTED' AND t.amount < 0)
                                        AND NOT EXISTS (SELECT 1 FROM wallet_transactions t
                                                        WHERE t.type = 'REFUND' AND t.payment_ref = 'refund-' || b.id::text)
                                        THEN 'LEDGER_DEBITE_STATUT_NON_CHARGE'
                                   WHEN b.commission_status = 'REFUND_FAILED'
                                        THEN 'REMBOURSEMENT_ECHOUE'
                                   WHEN b.commission_status = 'CHARGED' AND b.status IN ('CANCELLED','REJECTED')
                                        AND b.updated_at < now() - interval '1 hour'
                                        THEN 'CHARGEE_SUR_BID_ANNULE'
                                   WHEN b.payment_method = 'CASH'
                                        AND b.status IN ('ACCEPTED','HANDED_OVER','IN_TRANSIT','ARRIVED','COMPLETED')
                                        AND b.commission_status IS DISTINCT FROM 'CHARGED'
                                        AND b.commission_status IS DISTINCT FROM 'REFUNDED'
                                        THEN 'CASH_ACCEPTE_SANS_COMMISSION'
                               END AS anomaly
                        FROM bids b
                        WHERE (b.commission_status IS NOT NULL OR b.payment_method = 'CASH')
                          -- Colis issu d'une négociation : la commission vit sur le fil (negotiation_threads),
                          -- le débit wallet n'y porte pas de bid_id.
                          AND b.linked_negotiation_thread_id IS NULL
                    ) x
                    WHERE anomaly IS NOT NULL
                    """),
            new MoneyInvariant("INV-11", "Aucune opération mobile money bloquée plus d'1 h", Severity.HAUTE, """
                    SELECT o.id, o.kind, o.purpose, o.status, o.amount, o.currency, o.provider, o.payment_id, o.user_id,
                           o.created_at, o.submitted_at, o.callback_received_at, o.last_polled_at, now() - o.created_at AS age
                    FROM pawapay_operations o
                    WHERE o.status IN ('CREATED','ACCEPTED','PROCESSING','ENQUEUED','IN_RECONCILIATION')
                      AND o.created_at < now() - interval '1 hour'
                    """),
            new MoneyInvariant("INV-12", "Paiement mobile money cohérent avec ses opérations", Severity.CRITIQUE, """
                    WITH ops AS (
                        SELECT o.payment_id,
                               bool_or(o.kind = 'DEPOSIT' AND o.status = 'COMPLETED') AS deposit_done,
                               max(o.amount) FILTER (WHERE o.kind = 'DEPOSIT' AND o.status = 'COMPLETED') AS deposit_amount,
                               bool_or(o.kind = 'PAYOUT' AND o.status = 'COMPLETED') AS payout_done,
                               bool_or(o.kind = 'PAYOUT' AND o.status NOT IN ('COMPLETED','FAILED','SUBMIT_REJECTED')) AS payout_open,
                               max(o.amount) FILTER (WHERE o.kind = 'PAYOUT' AND o.status = 'COMPLETED') AS payout_amount,
                               bool_or(o.kind = 'REFUND' AND o.status = 'COMPLETED') AS refund_done,
                               bool_or(o.kind = 'REFUND' AND o.status NOT IN ('COMPLETED','FAILED','SUBMIT_REJECTED')) AS refund_open
                        FROM pawapay_operations o
                        WHERE o.payment_id IS NOT NULL
                        GROUP BY o.payment_id
                    )
                    SELECT * FROM (
                        SELECT p.id AS payment_id, p.status, p.amount, p.commission_amount, p.currency,
                               p.created_at, p.escrow_released_at, o.deposit_amount, o.payout_amount,
                               COALESCE(o.payout_done, false) AS payout_done, COALESCE(o.payout_open, false) AS payout_open,
                               COALESCE(o.refund_done, false) AS refund_done, COALESCE(o.refund_open, false) AS refund_open,
                               CASE
                                   WHEN p.status IN ('ESCROW','RELEASED','REFUNDED') AND NOT COALESCE(o.deposit_done, false)
                                        THEN 'FONDS_SANS_DEPOT_COMPLETE'
                                   WHEN COALESCE(o.payout_done, false) AND COALESCE(o.refund_done, false)
                                        THEN 'VERSE_ET_REMBOURSE'
                                   WHEN o.payout_amount > o.deposit_amount
                                        THEN 'VERSEMENT_SUPERIEUR_AU_DEPOT'
                                   WHEN p.status = 'ESCROW' AND (COALESCE(o.payout_done, false) OR COALESCE(o.payout_open, false))
                                        THEN 'ESCROW_AVEC_VERSEMENT'
                                   WHEN p.status = 'RELEASED' AND NOT (COALESCE(o.payout_done, false) OR COALESCE(o.payout_open, false))
                                        AND p.escrow_released_at < now() - interval '1 hour'
                                        THEN 'LIBERE_SANS_VERSEMENT_VIVANT'
                                   WHEN p.status = 'REFUNDED' AND NOT (COALESCE(o.refund_done, false) OR COALESCE(o.refund_open, false))
                                        AND (SELECT max(a.created_at) FROM audit_log a
                                             WHERE a.entity_type = 'PAYMENT' AND a.entity_id = p.id) < now() - interval '1 hour'
                                        THEN 'REMBOURSE_SANS_REFUND_VIVANT'
                                   WHEN p.status IN ('PENDING','CANCELLED','FAILED') AND COALESCE(o.deposit_done, false)
                                        AND NOT (COALESCE(o.refund_done, false) OR COALESCE(o.refund_open, false))
                                        AND p.created_at < now() - interval '1 hour'
                                        THEN 'DEPOT_ENCAISSE_NON_APPLIQUE_NI_RESTITUE'
                                   WHEN o.deposit_amount IS NOT NULL AND o.deposit_amount <> p.amount
                                        THEN 'DEPOT_MONTANT_DIFFERENT'
                               END AS anomaly
                        FROM payments p
                        LEFT JOIN ops o ON o.payment_id = p.id
                        WHERE p.deleted_at IS NULL AND p.rail = 'PAWAPAY'
                    ) x
                    WHERE anomaly IS NOT NULL
                    """),
            new MoneyInvariant("INV-13", "Recharge mobile money réussie créditée une fois et une seule", Severity.CRITIQUE, """
                    SELECT o.id AS operation_id, o.user_id, o.status, o.amount, o.currency, o.finalized_at,
                           t.id AS wallet_tx_id, t.user_id AS tx_user_id, t.type AS tx_type, t.amount AS tx_amount, t.currency AS tx_currency
                    FROM pawapay_operations o
                    LEFT JOIN wallet_transactions t ON t.idempotency_key = 'pawapay-topup-' || o.id::text
                    WHERE o.purpose = 'WALLET_TOPUP' AND o.kind = 'DEPOSIT'
                      AND (
                            (o.status = 'COMPLETED' AND t.id IS NULL
                             AND COALESCE(o.finalized_at, o.updated_at) < now() - interval '10 minutes')
                         OR (t.id IS NOT NULL AND (o.status <> 'COMPLETED' OR t.type <> 'TOP_UP' OR t.amount <> o.amount
                                                   OR t.currency <> o.currency OR t.user_id <> o.user_id))
                      )
                    """),
            new MoneyInvariant("INV-14", "Aucune demande de remboursement wallet bloquée", Severity.MOYENNE, """
                    SELECT r.id AS request_id, r.user_id, r.currency, r.amount, r.status, r.channel, r.requested_at, r.resolved_at,
                           COUNT(i.id) FILTER (WHERE i.status IN ('PENDING','PROCESSING')) AS items_in_flight,
                           COUNT(i.id) FILTER (WHERE i.status = 'FAILED') AS items_failed,
                           COALESCE(SUM(i.amount) FILTER (WHERE i.status = 'REFUNDED'), 0) AS items_refunded
                    FROM wallet_refund_requests r
                    LEFT JOIN wallet_refund_request_items i ON i.refund_request_id = r.id
                    WHERE r.deleted_at IS NULL
                    GROUP BY r.id
                    HAVING (r.status = 'PENDING' AND r.requested_at < now() - interval '72 hours')
                        OR (r.status = 'PROCESSING' AND r.requested_at < now() - interval '24 hours')
                        OR (r.status NOT IN ('PENDING','PROCESSING') AND COUNT(i.id) FILTER (WHERE i.status IN ('PENDING','PROCESSING')) > 0)
                        OR COALESCE(SUM(i.amount) FILTER (WHERE i.status IN ('PENDING','PROCESSING','REFUNDED')), 0) > r.amount
                    """),
            new MoneyInvariant("INV-15", "Recharge jamais remboursée au-delà de son montant", Severity.CRITIQUE, """
                    SELECT t.id AS topup_tx_id, t.user_id, t.currency, t.amount AS topup_amount, t.payment_ref,
                           SUM(i.amount) AS refund_items_total, COUNT(i.id) AS nb_items,
                           bool_or(t.type <> 'TOP_UP') AS source_not_topup
                    FROM wallet_refund_request_items i
                    JOIN wallet_transactions t ON t.id = i.wallet_transaction_id
                    WHERE i.status IN ('PENDING','PROCESSING','REFUNDED')
                    GROUP BY t.id, t.user_id, t.currency, t.amount, t.payment_ref
                    HAVING SUM(i.amount) > t.amount OR bool_or(t.type <> 'TOP_UP')
                    """),
            new MoneyInvariant("INV-16", "Aucun webhook Stripe en échec définitif ou en retard", Severity.HAUTE, """
                    SELECT status, source, event_type, COUNT(*) AS n, MIN(received_at) AS oldest, MAX(retry_count) AS max_retry
                    FROM stripe_event_inbox
                    WHERE status IN ('FAILED','DEAD_LETTER')
                       OR (status = 'RECEIVED' AND received_at < now() - interval '15 minutes')
                    GROUP BY status, source, event_type
                    """),
            new MoneyInvariant("INV-17", "Même devise partout : colis, paiement, mobile money, commission", Severity.HAUTE, """
                    SELECT 'PAYMENT_VS_BID' AS kind, p.id AS ref_id, p.currency AS money_currency, b.currency AS expected_currency
                    FROM payments p JOIN bids b ON b.id = p.bid_id
                    WHERE p.deleted_at IS NULL AND p.currency <> b.currency
                    UNION ALL
                    SELECT 'PAYMENT_VS_THREAD', p.id, p.currency, nt.currency
                    FROM payments p JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
                    WHERE p.deleted_at IS NULL AND p.currency <> nt.currency
                    UNION ALL
                    SELECT 'PAWAPAY_OP_VS_PAYMENT', o.id, o.currency, p.currency
                    FROM pawapay_operations o JOIN payments p ON p.id = o.payment_id
                    WHERE o.currency <> p.currency
                    UNION ALL
                    SELECT 'COMMISSION_LEDGER_VS_BID', t.id, t.currency, b.currency
                    FROM wallet_transactions t JOIN bids b ON b.id = t.bid_id
                    WHERE t.type = 'COMMISSION_DEDUCTED' AND t.currency <> b.currency
                      AND (t.source_currency IS NULL OR t.source_currency <> b.currency)
                    UNION ALL
                    SELECT 'REFUND_ITEM_VS_REQUEST', i.id, t.currency, r.currency
                    FROM wallet_refund_request_items i
                    JOIN wallet_refund_requests r ON r.id = i.refund_request_id
                    JOIN wallet_transactions t ON t.id = i.wallet_transaction_id
                    WHERE t.currency <> r.currency
                    """)
    );
}
