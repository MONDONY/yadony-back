package com.yadony.api.payments.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Rejoue les transactions d'une devise dans l'ordre chronologique pour attribuer chaque
 * dépense à une origine de fonds :
 * <ol>
 *   <li>TOP_UP avec paymentRef ouvre un seau cash remboursable sur ce PaymentIntent ;</li>
 *   <li>tout autre crédit (parrainage, REFUND interne, TOP_UP sans paymentRef) va dans un
 *       seau non-cash unique ;</li>
 *   <li>un débit consomme le non-cash d'abord, puis les seaux cash du plus récent au plus ancien ;</li>
 *   <li>SELF_REFUND_OUT / ADMIN_REFUND_OUT sont rapprochés d'une demande de remboursement par
 *       leur montant (somme des items REFUNDED d'une même demande) et consomment les seaux de
 *       ses recharges ; sans correspondance, repli LIFO ;</li>
 *   <li>FORFEITED_ON_DELETION consomme le non-cash.</li>
 * </ol>
 * Une recharge portant un item PENDING, PROCESSING ou FAILED est retirée du remboursable
 * et comptée en {@code inFlight}. Pur : aucune dépendance Spring, aucune I/O.
 *
 * <p>Limite connue (règle 4) : un {@code ADMIN_REFUND_OUT} issu de la résolution d'un ticket
 * enfant n'a pas d'items REFUNDED et retombe donc en LIFO. Si l'utilisateur a rechargé entre
 * l'échec Stripe et la résolution admin, la recharge fraîche peut être déclarée consommée à
 * la place de la recharge en échec, qui reste bloquée par son item FAILED. Une traçabilité
 * par items sur le ticket enfant est prévue en suite : elle exige un second item par
 * PaymentIntent, donc la levée de l'index {@code uq_wallet_refund_request_items_pi}.
 */
public final class WalletRefundAllocator {

    private static final Set<WalletTransactionType> REFUND_OUT =
            EnumSet.of(WalletTransactionType.SELF_REFUND_OUT, WalletTransactionType.ADMIN_REFUND_OUT);

    private static final Set<WalletRefundItemStatus> BLOCKING =
            EnumSet.of(WalletRefundItemStatus.PENDING, WalletRefundItemStatus.PROCESSING,
                    WalletRefundItemStatus.FAILED);

    private WalletRefundAllocator() {}

    private static final class Bucket {
        final UUID transactionId;
        final String paymentIntentId;
        BigDecimal remaining;

        Bucket(UUID transactionId, String paymentIntentId, BigDecimal remaining) {
            this.transactionId = transactionId;
            this.paymentIntentId = paymentIntentId;
            this.remaining = remaining;
        }
    }

    /** Items REFUNDED d'une même demande, appariés à une transaction sortante par leur somme. */
    private static final class RefundedRequest {
        final BigDecimal total;
        final List<WalletRefundRequestItemEntity> items;
        final Instant earliestCreatedAt;

        RefundedRequest(BigDecimal total, List<WalletRefundRequestItemEntity> items, Instant earliestCreatedAt) {
            this.total = total;
            this.items = items;
            this.earliestCreatedAt = earliestCreatedAt;
        }
    }

    /**
     * Préconditions à la charge de l'appelant (non vérifiées ici, l'allocateur est pur) :
     * <ul>
     *   <li>{@code ledgerAsc} contient les transactions d'un seul wallet et d'une seule devise,
     *       triées par ordre chronologique croissant (le rejeu applique les mouvements dans cet
     *       ordre, la date de chaque transaction n'est pas relue) ;</li>
     *   <li>{@code items} contient les items de demande de remboursement (tout statut confondu)
     *       de ce même wallet et de cette même devise ;</li>
     *   <li>l'appariement d'un {@code SELF_REFUND_OUT}/{@code ADMIN_REFUND_OUT} à une demande se
     *       fait par égalité de montant avec la plus ancienne demande non encore appliquée dont la
     *       somme des items {@code REFUNDED} correspond, l'ancienneté étant le {@code createdAt}
     *       minimal des items du groupe (les items sans {@code createdAt} sont classés en
     *       dernier).</li>
     * </ul>
     */
    public static WalletRefundAllocation allocate(List<WalletTransactionEntity> ledgerAsc,
                                                  List<WalletRefundRequestItemEntity> items,
                                                  BigDecimal balance) {
        List<Bucket> buckets = new ArrayList<>();
        Map<UUID, Bucket> bucketByTx = new HashMap<>();
        BigDecimal nonCash = BigDecimal.ZERO;
        BigDecimal unallocated = BigDecimal.ZERO;
        Deque<RefundedRequest> refundedQueue = refundedRequests(items);

        for (WalletTransactionEntity tx : ledgerAsc) {
            BigDecimal amount = tx.getAmount();
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            if (amount.signum() > 0) {
                if (tx.getType() == WalletTransactionType.TOP_UP
                        && tx.getPaymentRef() != null && !tx.getPaymentRef().isBlank()) {
                    Bucket b = new Bucket(tx.getId(), tx.getPaymentRef(), amount);
                    buckets.add(b);
                    bucketByTx.put(tx.getId(), b);
                } else {
                    nonCash = nonCash.add(amount);
                }
                continue;
            }

            BigDecimal debit = amount.negate();
            if (tx.getType() == WalletTransactionType.FORFEITED_ON_DELETION) {
                BigDecimal taken = nonCash.min(debit);
                nonCash = nonCash.subtract(taken);
                unallocated = unallocated.add(debit.subtract(taken));
                continue;
            }
            if (REFUND_OUT.contains(tx.getType())) {
                // La plus ancienne demande non encore appliquée dont le total égale le débit
                // courant est retenue : ne tester que la tête de file laisserait une demande
                // non appariée bloquer indéfiniment les suivantes.
                RefundedRequest matched = null;
                for (Iterator<RefundedRequest> it = refundedQueue.iterator(); it.hasNext();) {
                    RefundedRequest candidate = it.next();
                    if (candidate.total.compareTo(debit) == 0) {
                        matched = candidate;
                        it.remove();
                        break;
                    }
                }
                if (matched != null) {
                    for (WalletRefundRequestItemEntity item : matched.items) {
                        Bucket b = bucketByTx.get(item.getWalletTransactionId());
                        if (b == null) {
                            unallocated = unallocated.add(item.getAmount());
                            continue;
                        }
                        BigDecimal taken = b.remaining.min(item.getAmount());
                        b.remaining = b.remaining.subtract(taken);
                        unallocated = unallocated.add(item.getAmount().subtract(taken));
                    }
                    continue;
                }
            }
            // Débit ordinaire, ou sortie de remboursement sans demande appariée : non-cash puis LIFO.
            BigDecimal left = debit;
            BigDecimal fromNonCash = nonCash.min(left);
            nonCash = nonCash.subtract(fromNonCash);
            left = left.subtract(fromNonCash);
            for (int i = buckets.size() - 1; i >= 0 && left.signum() > 0; i--) {
                Bucket b = buckets.get(i);
                BigDecimal taken = b.remaining.min(left);
                b.remaining = b.remaining.subtract(taken);
                left = left.subtract(taken);
            }
            unallocated = unallocated.add(left);
        }

        Set<UUID> blocked = new HashSet<>();
        for (WalletRefundRequestItemEntity item : items) {
            if (BLOCKING.contains(item.getStatus())) {
                blocked.add(item.getWalletTransactionId());
            }
        }

        List<WalletRefundAllocation.RefundableTopup> refundable = new ArrayList<>();
        BigDecimal refundableTotal = BigDecimal.ZERO;
        BigDecimal inFlight = BigDecimal.ZERO;
        for (Bucket b : buckets) {
            if (b.remaining.signum() <= 0) {
                continue;
            }
            if (blocked.contains(b.transactionId)) {
                inFlight = inFlight.add(b.remaining);
                continue;
            }
            refundable.add(new WalletRefundAllocation.RefundableTopup(b.transactionId, b.paymentIntentId, b.remaining));
            refundableTotal = refundableTotal.add(b.remaining);
        }

        BigDecimal computed = refundableTotal.add(nonCash).add(inFlight);
        if (unallocated.signum() != 0 || computed.compareTo(balance) != 0) {
            throw new WalletAllocationInvariantException(computed, balance, unallocated);
        }
        return new WalletRefundAllocation(List.copyOf(refundable), refundableTotal, nonCash, inFlight);
    }

    private static Deque<RefundedRequest> refundedRequests(List<WalletRefundRequestItemEntity> items) {
        Map<UUID, List<WalletRefundRequestItemEntity>> byRequest = new LinkedHashMap<>();
        for (WalletRefundRequestItemEntity item : items) {
            if (item.getStatus() == WalletRefundItemStatus.REFUNDED) {
                byRequest.computeIfAbsent(item.getRefundRequestId(), k -> new ArrayList<>()).add(item);
            }
        }
        List<RefundedRequest> ordered = new ArrayList<>();
        for (List<WalletRefundRequestItemEntity> group : byRequest.values()) {
            BigDecimal total = group.stream().map(WalletRefundRequestItemEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Instant earliest = group.stream().map(WalletRefundRequestItemEntity::getCreatedAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            ordered.add(new RefundedRequest(total, group, earliest));
        }
        ordered.sort(Comparator.comparing((RefundedRequest r) -> r.earliestCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return new ArrayDeque<>(ordered);
    }
}
