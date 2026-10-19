package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidSupplementSummaryPort;
import com.yadony.api.matching.BidSupplementView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Données du supplément pour {@code BidResponse} : le supplément à afficher (l'actif, sinon le
 * dernier clos), le droit de demander ({@code supplementRequestAllowed}, seule source du bouton de
 * l'app) et les montants payés à ajouter aux totaux. Les listes passent par {@link #prefetch} : une
 * requête par lot, pas une par colis.
 */
@Component
public class BidSupplementSummaryAdapter implements BidSupplementSummaryPort {

    /** Taille d'un lot {@code IN (...)}, comme {@code BidResponsePrefetch.CHUNK_SIZE}. */
    private static final int CHUNK_SIZE = 500;

    private static final Set<BidSupplementStatus> EXCUSING = EnumSet.of(BidSupplementStatus.DECLINED,
            BidSupplementStatus.EXPIRED);

    private static final Comparator<BidSupplementEntity> NEWEST_FIRST =
            Comparator.comparing(BidSupplementEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final BidSupplementRepository supplements;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;
    private final Set<BidSupplementRail> supportedRails;
    private final StorageService storage;
    private final UserRepository users;

    public BidSupplementSummaryAdapter(BidSupplementRepository supplements, BidSupplementWindow window,
                                       BidSupplementProperties properties, List<BidSupplementRailHandler> handlers,
                                       StorageService storage, UserRepository users) {
        this.supplements = supplements;
        this.window = window;
        this.properties = properties;
        this.supportedRails = handlers.stream().map(BidSupplementRailHandler::rail)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(BidSupplementRail.class)));
        this.storage = storage;
        this.users = users;
    }

    /** Suppléments d'une liste, regroupés par colis. */
    record Loaded(Map<UUID, List<BidSupplementEntity>> byBid) implements Prefetch {
    }

    @Override
    public Prefetch prefetch(Collection<UUID> bidIds) {
        List<UUID> distinct = new ArrayList<>(new java.util.HashSet<>(bidIds));
        Map<UUID, List<BidSupplementEntity>> byBid = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += CHUNK_SIZE) {
            supplements.findByBidIdIn(distinct.subList(from, Math.min(from + CHUNK_SIZE, distinct.size())))
                    .forEach(s -> byBid.computeIfAbsent(s.getBidId(), k -> new ArrayList<>()).add(s));
        }
        return new Loaded(byBid);
    }

    @Override
    public BidSupplementView view(BidEntity bid, AnnouncementEntity announcement, UUID callerId, Prefetch prefetch) {
        List<BidSupplementEntity> all = supplementsOf(bid.getId(), prefetch);
        BidSupplementEntity shown = all.stream().filter(s -> BidSupplementStatus.ACTIVE.contains(s.getStatus()))
                .findFirst().orElse(all.isEmpty() ? null : all.get(0));
        boolean forSender = callerId != null && callerId.equals(bid.getSenderId());
        boolean paid = shown != null && BidSupplementStatus.PAID_STATES.contains(shown.getStatus());
        return new BidSupplementView(
                shown == null ? null : BidSupplementViews.of(shown, forSender, storage, window.now()),
                requestAllowed(bid, announcement, callerId, all),
                paid ? shown.getGrossAmount() : null,
                paid ? shown.getNetAmount() : null);
    }

    @Override
    public boolean excusesParcelRefusal(UUID bidId) {
        List<BidSupplementEntity> all = supplementsOf(bidId, null);
        return !all.isEmpty() && EXCUSING.contains(all.get(0).getStatus());
    }

    private boolean requestAllowed(BidEntity bid, AnnouncementEntity announcement, UUID callerId,
                                   List<BidSupplementEntity> all) {
        return properties.isEnabled()
                && announcement != null
                && callerId != null && callerId.equals(announcement.getTravelerId())
                && all.size() < properties.getMaxPerBid()
                && all.stream().noneMatch(s -> BidSupplementStatus.ACTIVE.contains(s.getStatus()))
                && window.isOpen(bid, announcement)
                && railPayable(bid, announcement);
    }

    /**
     * Rail livré ET voyageur capable de recevoir sur ce rail : la même règle que la création, pour
     * qu'aucun bouton n'ouvre une demande refusée en 422. Le voyageur est déjà chargé par la
     * conversion du colis : la lecture ne coûte pas de requête.
     */
    private boolean railPayable(BidEntity bid, AnnouncementEntity announcement) {
        BidSupplementRail rail;
        try {
            rail = BidSupplementRail.from(bid.getPaymentMethod());
        } catch (YadonyBusinessException legacyRail) {
            return false;
        }
        return supportedRails.contains(rail)
                && users.findById(announcement.getTravelerId())
                        .map(traveler -> rail.payableBy(traveler, bid.getCurrency()))
                        .orElse(false);
    }

    private List<BidSupplementEntity> supplementsOf(UUID bidId, Prefetch prefetch) {
        List<BidSupplementEntity> all = prefetch instanceof Loaded loaded
                ? new ArrayList<>(loaded.byBid().getOrDefault(bidId, List.of()))
                : new ArrayList<>(supplements.findByBidIdOrderByCreatedAtDesc(bidId));
        all.sort(NEWEST_FIRST);
        return all;
    }
}
