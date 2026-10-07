package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportTargetType;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Retrouve la cible d'un signalement et l'auteur du contenu signalé, en une requête par table
 * pour toute une page de signalements (plus une lecture Firestore par message signalé et une
 * requête sur les UID Firebase des expéditeurs de messages).
 *
 * <p>Auteur selon la cible :
 * <ul>
 *   <li>USER : le compte signalé ;</li>
 *   <li>ANNOUNCEMENT : le voyageur ; PACKAGE_REQUEST : l'expéditeur ;</li>
 *   <li>RATING : le notant ({@code rater_id}) ; un avis anonyme du destinataire n'a pas d'auteur ;</li>
 *   <li>MESSAGE : l'expéditeur du message dans Firestore, dont le {@code senderId} est l'UID
 *       Firebase du compte (un UUID reste accepté ; {@code SYSTEM} : pas d'auteur) ;</li>
 *   <li>BID : la partie adverse du signalant. Une offre lie un expéditeur ({@code bids.sender_id})
 *       et un voyageur (le {@code traveler_id} de l'annonce). Signalée par l'expéditeur, l'auteur
 *       est le voyageur ; par le voyageur, l'expéditeur. Signalée par un tiers (ou un signalant
 *       inconnu), aucun auteur : on ne devine pas qui sanctionner.</li>
 * </ul>
 * Un auteur supprimé n'est pas relu ({@code @Where deleted_at IS NULL} sur les comptes) :
 * il est traité comme inconnu.
 */
@Component
public class ReportTargetResolver {

    private final UserRepository userRepo;
    private final AnnouncementRepository announcementRepo;
    private final PackageRequestRepository packageRequestRepo;
    private final BidRepository bidRepo;
    private final RatingRepository ratingRepo;
    private final ConversationRepository conversationRepo;
    private final FirestoreService firestoreService;

    public ReportTargetResolver(UserRepository userRepo,
                                AnnouncementRepository announcementRepo,
                                PackageRequestRepository packageRequestRepo,
                                BidRepository bidRepo,
                                RatingRepository ratingRepo,
                                ConversationRepository conversationRepo,
                                FirestoreService firestoreService) {
        this.userRepo = userRepo;
        this.announcementRepo = announcementRepo;
        this.packageRequestRepo = packageRequestRepo;
        this.bidRepo = bidRepo;
        this.ratingRepo = ratingRepo;
        this.conversationRepo = conversationRepo;
        this.firestoreService = firestoreService;
    }

    public ResolvedReportTarget resolve(ReportEntity report) {
        return resolve(List.of(report)).get(report);
    }

    /** Cible de chaque signalement, indexée par instance (un signalement neuf n'a pas toujours d'id). */
    public Map<ReportEntity, ResolvedReportTarget> resolve(Collection<ReportEntity> reports) {
        Map<UUID, BidEntity> bids = load(bidRepo, targetIds(reports, ReportTargetType.BID), BidEntity::getId);
        Set<UUID> announcementIds = new HashSet<>(targetIds(reports, ReportTargetType.ANNOUNCEMENT));
        bids.values().forEach(b -> announcementIds.add(b.getAnnouncementId()));
        announcementIds.remove(null);
        Map<UUID, AnnouncementEntity> announcements = load(announcementRepo, announcementIds, AnnouncementEntity::getId);
        Map<UUID, PackageRequestEntity> packageRequests = load(packageRequestRepo,
                targetIds(reports, ReportTargetType.PACKAGE_REQUEST), PackageRequestEntity::getId);
        Map<UUID, RatingEntity> ratings = load(ratingRepo, targetIds(reports, ReportTargetType.RATING), RatingEntity::getId);
        Map<UUID, ConversationEntity> conversations = load(conversationRepo,
                reports.stream()
                        .filter(r -> r.getTargetType() == ReportTargetType.MESSAGE && r.getTargetMessageId() != null)
                        .map(ReportEntity::getTargetId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()),
                ConversationEntity::getId);

        // Première passe : cible retrouvée et identifiant de l'auteur.
        Map<ReportEntity, Partial> partials = new IdentityHashMap<>();
        for (ReportEntity r : reports) {
            partials.put(r, partial(r, bids, announcements, packageRequests, ratings, conversations));
        }

        // Seconde passe : les auteurs, en une requête par identifiant (compte, puis UID Firebase
        // des expéditeurs de messages).
        Set<UUID> authorIds = partials.values().stream()
                .map(Partial::authorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserEntity> users = load(userRepo, authorIds, UserEntity::getId);
        Set<String> authorUids = partials.values().stream()
                .map(Partial::authorUid).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserEntity> usersByUid = authorUids.isEmpty() ? Map.of()
                : MessageSenders.resolve(userRepo, authorUids);

        Map<ReportEntity, ResolvedReportTarget> result = new IdentityHashMap<>();
        partials.forEach((r, p) -> {
            UserEntity author = p.authorId() != null ? users.get(p.authorId())
                    : p.authorUid() != null ? usersByUid.get(p.authorUid()) : null;
            boolean found = r.getTargetType() == ReportTargetType.USER ? author != null : p.found();
            result.put(r, found || author != null
                    ? new ResolvedReportTarget(found, p.alreadyModerated(), author, p.conversation(), p.messageId())
                    : ResolvedReportTarget.none());
        });
        return result;
    }

    /**
     * {@code authorUid} : UID Firebase de l'expéditeur d'un message, quand Firestore ne porte pas
     * l'identifiant du compte ({@code senderId} = UID Firebase, cf. {@link MessageSenders}).
     */
    private record Partial(boolean found, boolean alreadyModerated, UUID authorId, String authorUid,
                           ConversationEntity conversation, String messageId) {
        static final Partial NONE = new Partial(false, false, null, null, null);

        Partial(boolean found, boolean alreadyModerated, UUID authorId,
                ConversationEntity conversation, String messageId) {
            this(found, alreadyModerated, authorId, null, conversation, messageId);
        }
    }

    private Partial partial(ReportEntity r,
                            Map<UUID, BidEntity> bids,
                            Map<UUID, AnnouncementEntity> announcements,
                            Map<UUID, PackageRequestEntity> packageRequests,
                            Map<UUID, RatingEntity> ratings,
                            Map<UUID, ConversationEntity> conversations) {
        if (r.getTargetType() == null || r.getTargetId() == null) {
            return Partial.NONE;
        }
        UUID targetId = r.getTargetId();
        return switch (r.getTargetType()) {
            case USER -> new Partial(true, false, targetId, null, null);
            case ANNOUNCEMENT -> {
                AnnouncementEntity a = announcements.get(targetId);
                yield a == null ? Partial.NONE : new Partial(true, false, a.getTravelerId(), null, null);
            }
            case PACKAGE_REQUEST -> {
                PackageRequestEntity p = packageRequests.get(targetId);
                yield p == null ? Partial.NONE : new Partial(true, false, p.getSenderId(), null, null);
            }
            case RATING -> {
                RatingEntity rating = ratings.get(targetId);
                yield rating == null ? Partial.NONE
                        : new Partial(true, rating.isExcludedFromAverage(), rating.getRaterId(), null, null);
            }
            case BID -> {
                BidEntity bid = bids.get(targetId);
                if (bid == null) yield Partial.NONE;
                AnnouncementEntity a = announcements.get(bid.getAnnouncementId());
                UUID traveler = a != null ? a.getTravelerId() : null;
                yield new Partial(true, false, counterparty(r.getReporterId(), bid.getSenderId(), traveler), null, null);
            }
            case MESSAGE -> message(r, conversations.get(targetId));
            case APP -> Partial.NONE;
        };
    }

    /** Partie adverse du signalant sur une offre ; {@code null} si le signalant n'y est pas partie. */
    static UUID counterparty(UUID reporterId, UUID senderId, UUID travelerId) {
        if (reporterId == null) return null;
        if (reporterId.equals(senderId)) return travelerId;
        if (reporterId.equals(travelerId)) return senderId;
        return null;
    }

    private Partial message(ReportEntity r, ConversationEntity conversation) {
        if (conversation == null || conversation.getFirestoreConversationId() == null || r.getTargetMessageId() == null) {
            return Partial.NONE;
        }
        return firestoreService.findMessage(conversation.getFirestoreConversationId(), r.getTargetMessageId())
                // senderId = UID Firebase (app et back) ; UUID accepté ; SYSTEM : pas d'auteur.
                .map(m -> new Partial(true, m.deleted(), MessageSenders.asUserId(m.senderId()),
                        MessageSenders.asFirebaseUid(m.senderId()), conversation, r.getTargetMessageId()))
                .orElse(Partial.NONE);
    }

    private static Set<UUID> targetIds(Collection<ReportEntity> reports, ReportTargetType type) {
        return reports.stream()
                .filter(r -> r.getTargetType() == type)
                .map(ReportEntity::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static <T> Map<UUID, T> load(CrudRepository<T, UUID> repo, Set<UUID> ids, Function<T, UUID> idOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, T> byId = new java.util.HashMap<>();
        for (T entity : repo.findAllById(ids)) {
            UUID id = idOf.apply(entity);
            if (id != null) {
                byId.putIfAbsent(id, entity);
            }
        }
        return byId;
    }
}
