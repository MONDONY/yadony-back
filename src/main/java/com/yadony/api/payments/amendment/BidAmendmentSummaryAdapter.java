package com.yadony.api.payments.amendment;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidAmendmentSummaryPort;
import com.yadony.api.matching.BidAmendmentView;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.matching.dto.BidMoneyBreakdown;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Modifications d'un colis pour {@code BidResponse} : celles à afficher, le droit de modifier
 * ({@code amendmentAllowed}, seule source de l'encart de l'app), les kilos acquis et les transactions de la
 * ventilation de l'argent. Les listes passent par {@link #prefetch} : quelques requêtes par lot, aucune par colis.
 */
@Component
public class BidAmendmentSummaryAdapter implements BidAmendmentSummaryPort {

    /** Taille d'un lot {@code IN (...)}, comme {@code BidResponsePrefetch.CHUNK_SIZE}. */
    private static final int CHUNK_SIZE = 500;

    /** Une modification close reste affichée (encart « refusée, expirée, annulée ») pendant cette durée. */
    static final Duration RECENT = Duration.ofDays(7);

    private static final Comparator<BidAmendmentEntity> OLDEST_FIRST =
            Comparator.comparing(BidAmendmentEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));

    private final BidAmendmentRepository amendments;
    private final BidAmendmentItemRepository items;
    private final BidSupplementRepository supplements;
    private final AnnouncementPriceGridItemRepository gridItems;
    private final UserRepository users;
    private final BidAmendmentEligibility eligibility;
    private final BidAmendmentWindow window;
    private final ObjectProvider<BidAmendmentRailHandler> handlers;

    public BidAmendmentSummaryAdapter(BidAmendmentRepository amendments, BidAmendmentItemRepository items,
                                      BidSupplementRepository supplements, AnnouncementPriceGridItemRepository gridItems,
                                      UserRepository users, BidAmendmentEligibility eligibility,
                                      BidAmendmentWindow window, ObjectProvider<BidAmendmentRailHandler> handlers) {
        this.amendments = amendments;
        this.items = items;
        this.supplements = supplements;
        this.gridItems = gridItems;
        this.users = users;
        this.eligibility = eligibility;
        this.window = window;
        this.handlers = handlers;
    }

    /**
     * Lectures d'une liste.
     *
     * @param byBid            modifications par colis.
     * @param itemsByAmendment articles des modifications.
     * @param eligibilityBids  colis {@code ACCEPTED} de la liste : les deux ensembles suivants les couvrent.
     * @param openSupplements  colis dont un supplément est ouvert.
     * @param gridAnnouncements trajets qui ont une grille de prix.
     */
    record Loaded(Map<UUID, List<BidAmendmentEntity>> byBid, Map<UUID, List<BidAmendmentItemEntity>> itemsByAmendment,
                  Set<UUID> eligibilityBids, Set<UUID> openSupplements, Set<UUID> gridAnnouncements)
            implements Prefetch {
    }

    @Override
    public Prefetch prefetch(Collection<BidEntity> bids) {
        List<UUID> bidIds = bids.stream().map(BidEntity::getId).distinct().toList();
        Map<UUID, List<BidAmendmentEntity>> byBid = new HashMap<>();
        inChunks(bidIds, amendments::findByBidIdIn)
                .forEach(a -> byBid.computeIfAbsent(a.getBidId(), k -> new ArrayList<>()).add(a));
        Map<UUID, List<BidAmendmentItemEntity>> itemsByAmendment = new HashMap<>();
        inChunks(byBid.values().stream().flatMap(List::stream).map(BidAmendmentEntity::getId).toList(),
                items::findByAmendmentIdIn)
                .forEach(i -> itemsByAmendment.computeIfAbsent(i.getAmendmentId(), k -> new ArrayList<>()).add(i));

        // amendmentAllowed : seul un colis accepté peut être modifié, les autres ne lisent rien de plus.
        List<BidEntity> accepted = bids.stream().filter(b -> b.getStatus() == BidStatus.ACCEPTED).toList();
        Set<UUID> eligibilityBids = new HashSet<>();
        accepted.forEach(b -> eligibilityBids.add(b.getId()));
        Set<UUID> openSupplements = new HashSet<>(inChunks(List.copyOf(eligibilityBids),
                chunk -> supplements.findBidIdsByBidIdInAndStatusIn(chunk, BidSupplementStatus.OPEN)));
        Set<UUID> gridAnnouncements = new HashSet<>(inChunks(
                accepted.stream().map(BidEntity::getAnnouncementId).filter(Objects::nonNull).distinct().toList(),
                gridItems::findAnnouncementIdsWithItems));
        return new Loaded(byBid, itemsByAmendment, eligibilityBids, openSupplements, gridAnnouncements);
    }

    @Override
    public BidAmendmentView view(BidEntity bid, AnnouncementEntity announcement, UUID callerId, Prefetch prefetch) {
        Loaded loaded = prefetch instanceof Loaded l ? l : null;
        List<BidAmendmentEntity> all = new ArrayList<>(loaded != null
                ? loaded.byBid().getOrDefault(bid.getId(), List.of())
                : amendments.findByBidIdOrderByCreatedAtDesc(bid.getId()));
        all.sort(OLDEST_FIRST);
        boolean forSender = callerId != null && callerId.equals(bid.getSenderId());
        List<BidAmendmentEntity> shown = shown(forSender ? all : all.stream().filter(a -> !hiddenFromTraveler(a))
                .toList());

        List<BidAmendmentResponse> responses = new ArrayList<>(shown.size());
        List<BidMoneyBreakdown.Entry> entries = new ArrayList<>();
        for (BidAmendmentEntity a : shown) {
            List<BidAmendmentItemEntity> lines = loaded != null
                    ? loaded.itemsByAmendment().getOrDefault(a.getId(), List.of())
                    : items.findByAmendmentId(a.getId());
            responses.add(BidAmendmentViews.of(a, lines, forSender, window));
            if (a.getStatus().isSecured() || a.getStatus().isOpen()) {
                boolean cash = a.getRail() == BidSupplementRail.CASH;
                entries.add(new BidMoneyBreakdown.Entry(BidMoneyBreakdown.KIND_AMENDMENT, a.getId(),
                        BidMoneyBreakdown.methodOf(a.getRail().name()), a.getNetAmount(), a.getGrossAmount(),
                        cash ? a.getCommissionAmount() : null, a.getStatus().isSecured()));
            }
        }
        BigDecimal securedKg = all.stream().filter(a -> a.getStatus().isSecured())
                .map(BidAmendmentEntity::getAddedWeightKg).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BidAmendmentView(List.copyOf(responses),
                forSender && allowed(bid, announcement, callerId, all, loaded), securedKg, List.copyOf(entries));
    }

    /**
     * Les acquises, puis la dernière modification non acquise si elle est ouverte ou close depuis moins de
     * {@link #RECENT} : l'encart de la réponse du voyageur reste visible une semaine, sans historique sans fin.
     */
    private List<BidAmendmentEntity> shown(List<BidAmendmentEntity> visible) {
        List<BidAmendmentEntity> shown = new ArrayList<>();
        BidAmendmentEntity latestOther = null;
        for (BidAmendmentEntity a : visible) {
            if (a.getStatus().isSecured()) {
                shown.add(a);
            } else {
                latestOther = a;
            }
        }
        if (latestOther != null && (latestOther.getStatus().isOpen() || recent(latestOther))) {
            shown.add(latestOther);
        }
        shown.sort(OLDEST_FIRST);
        return shown;
    }

    private boolean recent(BidAmendmentEntity a) {
        LocalDateTime at = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
        return at != null && at.isAfter(window.now().minus(RECENT));
    }

    /**
     * Le voyageur ne voit pas une demande qui ne lui a jamais été présentée ({@code proposedAt} nul) : montant carte
     * pas encore bloqué ({@code AUTHORIZATION_PENDING}), ou close avant l'autorisation (faute d'autorisation, retirée
     * par l'expéditeur, colis terminé).
     */
    static boolean hiddenFromTraveler(BidAmendmentEntity a) {
        return a.getProposedAt() == null;
    }

    /**
     * Même règle que les options ({@link BidAmendmentService#options}) : éligible ({@link BidAmendmentEligibility})
     * et au moins un moyen proposable dont le rail est livré, pour qu'aucun encart n'ouvre une demande refusée.
     */
    private boolean allowed(BidEntity bid, AnnouncementEntity announcement, UUID callerId,
                            List<BidAmendmentEntity> all, Loaded loaded) {
        if (announcement == null || bid.getStatus() != BidStatus.ACCEPTED) {
            return false;
        }
        UserEntity traveler = users.findById(announcement.getTravelerId()).orElse(null);
        // Les modifications du colis sont déjà lues ; le supplément et la grille viennent du lot s'il les couvre.
        boolean batched = loaded != null && loaded.eligibilityBids().contains(bid.getId());
        BidAmendmentEligibility.Facts single = batched ? null : eligibility.factsFor(bid, announcement);
        BidAmendmentEligibility.Facts facts = new BidAmendmentEligibility.Facts(
                () -> all.stream().anyMatch(a -> a.getStatus().isOpen()),
                batched ? () -> loaded.openSupplements().contains(bid.getId()) : single.supplementOpen(),
                () -> all.stream().filter(a -> a.getAcceptedAt() != null).count(),
                batched ? () -> loaded.gridAnnouncements().contains(announcement.getId()) : single.hasGridItems());
        if (eligibility.blockingReason(bid, announcement, traveler, callerId, false, facts).isPresent()) {
            return false;
        }
        for (PaymentMethod method : eligibility.offerableRails(bid, announcement, traveler)) {
            BidSupplementRail rail = BidSupplementRail.from(method);
            if (handlers.orderedStream().anyMatch(h -> h.rail() == rail)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> inChunks(List<UUID> ids, Function<List<UUID>, List<T>> loader) {
        List<T> all = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
            all.addAll(loader.apply(ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()))));
        }
        return all;
    }
}
