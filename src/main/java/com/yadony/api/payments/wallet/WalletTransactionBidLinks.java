package com.yadony.api.payments.wallet;

import com.yadony.api.matching.BidRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Colis lié à chaque ligne d'une page d'historique du solde (FLUTTER-QP), pour le lien
 * « voir le colis » de l'app ({@code WalletTransactionDto.bidId}).
 *
 * <ul>
 *   <li>{@code bid_id} de la ligne s'il est renseigné (débit de commission d'un colis classique,
 *       remboursement de commission écrit depuis FLUTTER-QP) ;</li>
 *   <li>commission d'un fil de négociation ({@code COMMISSION_DEDUCTED}, {@code bid_id} nul,
 *       {@code payment_ref} = id du fil) : le colis issu de ce fil
 *       ({@code bids.linked_negotiation_thread_id}) ;</li>
 *   <li>remboursement de commission antérieur ({@code REFUND}, {@code bid_id} nul,
 *       {@code payment_ref} = {@code refund-{bidId}}) : ce colis, s'il existe encore.</li>
 * </ul>
 * Une seule requête pour toute la page, aucune si toutes les lignes portent déjà leur colis.
 * Le grand livre est en ajout seul (V309) : les lignes anciennes ne sont jamais réécrites,
 * le lien se résout à la lecture.
 */
@Component
public class WalletTransactionBidLinks {

    static final String REFUND_REF_PREFIX = "refund-";
    /** Sentinelle d'une liste {@code IN} vide (aucun colis n'a cet id). */
    private static final UUID NONE = new UUID(0L, 0L);

    private final BidRepository bidRepository;

    public WalletTransactionBidLinks(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    /** Colis par id de transaction ; une transaction sans colis est absente de la table. */
    public Map<UUID, UUID> resolve(Collection<WalletTransactionEntity> transactions) {
        Map<UUID, UUID> result = new HashMap<>();
        Map<UUID, UUID> refundBidByTx = new HashMap<>();
        Map<UUID, UUID> threadByTx = new HashMap<>();
        for (WalletTransactionEntity tx : transactions) {
            if (tx.getBidId() != null) {
                result.put(tx.getId(), tx.getBidId());
            } else if (tx.getType() == WalletTransactionType.COMMISSION_DEDUCTED) {
                parseUuid(tx.getPaymentRef()).ifPresent(thread -> threadByTx.put(tx.getId(), thread));
            } else if (tx.getType() == WalletTransactionType.REFUND && tx.getPaymentRef() != null
                    && tx.getPaymentRef().startsWith(REFUND_REF_PREFIX)) {
                parseUuid(tx.getPaymentRef().substring(REFUND_REF_PREFIX.length()))
                        .ifPresent(bid -> refundBidByTx.put(tx.getId(), bid));
            }
        }
        if (refundBidByTx.isEmpty() && threadByTx.isEmpty()) {
            return result;
        }
        Set<UUID> ids = new HashSet<>(refundBidByTx.values());
        Set<UUID> threads = new HashSet<>(threadByTx.values());
        List<BidRepository.BidThreadLink> links = bidRepository.findBidThreadLinks(
                ids.isEmpty() ? Set.of(NONE) : ids, threads.isEmpty() ? Set.of(NONE) : threads);
        Set<UUID> existing = new HashSet<>();
        Map<UUID, UUID> bidByThread = new HashMap<>();
        for (BidRepository.BidThreadLink link : links) {
            existing.add(link.getId());
            if (link.getLinkedNegotiationThreadId() != null) {
                bidByThread.put(link.getLinkedNegotiationThreadId(), link.getId());
            }
        }
        refundBidByTx.forEach((tx, bid) -> {
            if (existing.contains(bid)) result.put(tx, bid);
        });
        threadByTx.forEach((tx, thread) -> {
            UUID bid = bidByThread.get(thread);
            if (bid != null) result.put(tx, bid);
        });
        return result;
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.length() != 36) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
