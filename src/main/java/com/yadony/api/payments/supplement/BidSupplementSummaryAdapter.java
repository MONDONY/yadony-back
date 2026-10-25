package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidSupplementSummaryPort;
import com.yadony.api.matching.BidSupplementView;
import com.yadony.api.payments.amendment.BidAmendmentRepository;
import com.yadony.api.payments.amendment.BidAmendmentStatus;
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

    /**
     * Dernière demande qui excuse le refus du colis (décisions du propriétaire du 19/10/2026) :
     * refusée ou laissée expirer par l'expéditeur, ou encore en attente de sa réponse (la fin du
     * colis la ferme ensuite).
     */
    private static final Set<BidSupplementStatus> EXCUSING = EnumSet.of(BidSupplementStatus.DECLINED,
            BidSupplementStatus.EXPIRED, BidSupplementStatus.PROPOSED, BidSupplementStatus.COMMISSION_PENDING);

    private static final Comparator<BidSupplementEntity> NEWEST_FIRST =
            Comparator.comparing(BidSupplementEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final BidSupplementRepository supplements;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;
    private final Map<BidSupplementRail, BidSupplementRailHandler> handlers;
    private final StorageService storage;
    private final UserRepository users;
    private final BidAmendmentRepository amendments;

    public BidSupplementSummaryAdapter(BidSupplementRepository supplements, BidSupplementWindow window,
                                       BidSupplementProperties properties, List<BidSupplementRailHandler> handlers,
                                       StorageService storage, UserRepository users,
                                       BidAmendmentRepository amendments) {
        this.supplements = supplements;
        this.window = window;
        this.properties = properties;
        // Un composant sans rail (doublure de test) ne débloque rien, comme dans BidSupplementPaymentFlow.
        this.handlers = handlers.stream().filter(h -> h.rail() != null).collect(Collectors.toMap(
                BidSupplementRailHandler::rail, h -> h, (a, b) -> a, () -> new java.util.EnumMap<>(BidSupplementRail.class)));
        this.storage = storage;
        this.users = users;
        this.amendments = amendments;
    }

    /** Suppléments d'une liste, regroupés par colis, et colis dont une modification est ouverte. */
    record Loaded(Map<UUID, List<BidSupplementEntity>> byBid, Set<UUID> openAmendments) implements Prefetch {
    }

    @Override
    public Prefetch prefetch(Collection<UUID> bidIds) {
        List<UUID> distinct = new ArrayList<>(new java.util.HashSet<>(bidIds));
        Map<UUID, List<BidSupplementEntity>> byBid = new HashMap<>();
        Set<UUID> openAmendments = new java.util.HashSet<>();
        for (int from = 0; from < distinct.size(); from += CHUNK_SIZE) {
            List<UUID> chunk = distinct.subList(from, Math.min(from + CHUNK_SIZE, distinct.size()));
            supplements.findByBidIdIn(chunk)
                    .forEach(s -> byBid.computeIfAbsent(s.getBidId(), k -> new ArrayList<>()).add(s));
            openAmendments.addAll(amendments.findBidIdsByBidIdInAndStatusIn(chunk, BidAmendmentStatus.OPEN));
        }
        return new Loaded(byBid, openAmendments);
    }

    @Override
    public BidSupplementView view(BidEntity bid, AnnouncementEntity announcement, UUID callerId, Prefetch prefetch) {
        List<BidSupplementEntity> all = supplementsOf(bid.getId(), prefetch);
        boolean forSender = callerId != null && callerId.equals(bid.getSenderId());
        // L'expéditeur ne voit pas une demande qui ne lui a jamais été adressée (commission espèces
        // en attente ou refusée) : il ne la retrouverait dans aucune notification.
        List<BidSupplementEntity> visible = forSender ? all.stream().filter(s -> !hiddenFromSender(s)).toList() : all;
        BidSupplementEntity shown = visible.stream().filter(s -> BidSupplementStatus.ACTIVE.contains(s.getStatus()))
                .findFirst().orElse(visible.isEmpty() ? null : visible.get(0));
        boolean paid = shown != null && BidSupplementStatus.PAID_STATES.contains(shown.getStatus());
        return new BidSupplementView(
                shown == null ? null : BidSupplementViews.of(shown, forSender, storage, window),
                requestAllowed(bid, announcement, callerId, all, prefetch),
                paid ? shown.getGrossAmount() : null,
                paid ? shown.getNetAmount() : null);
    }

    @Override
    public boolean excusesParcelRefusal(UUID bidId) {
        List<BidSupplementEntity> all = supplementsOf(bidId, null);
        return !all.isEmpty() && EXCUSING.contains(all.get(0).getStatus());
    }

    private boolean requestAllowed(BidEntity bid, AnnouncementEntity announcement, UUID callerId,
                                   List<BidSupplementEntity> all, Prefetch prefetch) {
        return properties.isEnabled()
                && announcement != null
                && callerId != null && callerId.equals(announcement.getTravelerId())
                && all.stream().filter(s -> !neverProposed(s)).count() < properties.getMaxPerBid()
                && all.stream().noneMatch(s -> BidSupplementStatus.ACTIVE.contains(s.getStatus()))
                && window.isOpen(bid, announcement)
                && railPayable(bid, announcement)
                && !amendmentOpen(bid.getId(), prefetch);
    }

    /** Une modification du colis est ouverte : même refus que la création ({@code supplement-already-active}). */
    private boolean amendmentOpen(UUID bidId, Prefetch prefetch) {
        return prefetch instanceof Loaded loaded
                ? loaded.openAmendments().contains(bidId)
                : amendments.existsByBidIdAndStatusIn(bidId, BidAmendmentStatus.OPEN);
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
        BidSupplementRailHandler handler = handlers.get(rail);
        return handler != null
                && users.findById(announcement.getTravelerId())
                        .map(traveler -> rail.payableBy(traveler, bid.getCurrency())
                                && handler.canRequest(traveler, bid.getCurrency()))
                        .orElse(false);
    }

    /**
     * Demande close sans avoir atteint l'expéditeur : commission espèces refusée, jamais confirmée,
     * ou retrait avant la commission. Hors plafond {@code max-per-bid}.
     */
    static boolean neverProposed(BidSupplementEntity s) {
        return s.getClosedReason() != null && BidSupplementService.NEVER_PROPOSED_REASONS.contains(s.getClosedReason());
    }

    /** L'expéditeur ne voit ni une demande close sans lui avoir été adressée, ni une demande en attente de commission. */
    static boolean hiddenFromSender(BidSupplementEntity s) {
        return s.getStatus() == BidSupplementStatus.COMMISSION_PENDING || neverProposed(s);
    }

    private List<BidSupplementEntity> supplementsOf(UUID bidId, Prefetch prefetch) {
        List<BidSupplementEntity> all = prefetch instanceof Loaded loaded
                ? new ArrayList<>(loaded.byBid().getOrDefault(bidId, List.of()))
                : new ArrayList<>(supplements.findByBidIdOrderByCreatedAtDesc(bidId));
        all.sort(NEWEST_FIRST);
        return all;
    }
}
