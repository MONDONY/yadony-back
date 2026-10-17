package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.assistant.ParcelDirectory;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.reception.BidRecipientLinkEntity;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Colis que l'assistant peut évoquer avec un utilisateur. Un colis n'est accessible qu'à son
 * expéditeur, à son voyageur et à son destinataire confirmé, mêmes règles que les écrans du colis :
 * un numéro DON-… saisi par quelqu'un d'autre ne révèle rien, pas même qu'il existe.
 */
@Component
public class AssistantParcelDirectory implements ParcelDirectory {

    /** Un colis clos reste proposé ce nombre de jours (comme la liste des réceptions). */
    static final long RECENT_DAYS = 14;

    /** De la demande à la livraison. */
    static final Set<BidStatus> ACTIVE = EnumSet.of(BidStatus.AWAITING_PAYMENT, BidStatus.PENDING,
            BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
            BidStatus.ARRIVED);

    static final Set<BidStatus> ENDED = EnumSet.of(BidStatus.COMPLETED, BidStatus.CANCELLED,
            BidStatus.REJECTED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED, BidStatus.EXPIRED);

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final BidRecipientLinkRepository linkRepository;
    private final Clock clock;

    @Autowired
    public AssistantParcelDirectory(UserRepository userRepository, BidRepository bidRepository,
                                    AnnouncementRepository announcementRepository,
                                    BidRecipientLinkRepository linkRepository) {
        this(userRepository, bidRepository, announcementRepository, linkRepository, Clock.systemUTC());
    }

    AssistantParcelDirectory(UserRepository userRepository, BidRepository bidRepository,
                             AnnouncementRepository announcementRepository,
                             BidRecipientLinkRepository linkRepository, Clock clock) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.linkRepository = linkRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParcelRef> recentParcels(String firebaseUid, int limit) {
        if (limit <= 0) return List.of();
        Optional<UserEntity> user = userRepository.findByFirebaseUid(firebaseUid);
        if (user.isEmpty()) return List.of();
        UUID userId = user.get().getId();
        LocalDateTime since = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).minusDays(RECENT_DAYS);
        PageRequest page = PageRequest.of(0, limit);

        // Un même colis sous deux rôles (expéditeur qui se l'envoie) : le premier rôle gagne.
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        for (BidEntity bid : bidRepository.findAssistantParcelsOfSender(userId, ACTIVE, ENDED, since, page)) {
            candidates.putIfAbsent(bid.getId(), new Candidate(bid, ParcelRole.SENDER));
        }
        for (BidEntity bid : bidRepository.findAssistantParcelsOfTraveler(userId, ACTIVE, ENDED, since, page)) {
            candidates.putIfAbsent(bid.getId(), new Candidate(bid, ParcelRole.TRAVELER));
        }
        List<UUID> receivedIds = linkRepository
                .findByRecipientUserIdAndStatusIn(userId, Set.of(ReceptionLinkStatus.CONFIRMED)).stream()
                .map(BidRecipientLinkEntity::getBidId).toList();
        for (BidEntity bid : bidRepository.findAllById(receivedIds)) {
            if (BidStatus.IN_FLIGHT.contains(bid.getStatus())
                    || (bid.getStatus() == BidStatus.COMPLETED && bid.getUpdatedAt() != null
                    && !bid.getUpdatedAt().isBefore(since))) {
                candidates.putIfAbsent(bid.getId(), new Candidate(bid, ParcelRole.RECIPIENT));
            }
        }

        Map<UUID, AnnouncementEntity> announcements = announcementRepository
                .findAllById(candidates.values().stream().map(c -> c.bid().getAnnouncementId())
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AnnouncementEntity::getId, Function.identity()));

        return candidates.values().stream()
                .sorted(Comparator.comparing((Candidate c) -> !ACTIVE.contains(c.bid().getStatus()))
                        .thenComparing(c -> c.bid().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(c -> toRef(c.bid(), userId, c.role(), announcements.get(c.bid().getAnnouncementId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ParcelRef> byTrackingNumber(String firebaseUid, String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) return Optional.empty();
        return userRepository.findByFirebaseUid(firebaseUid)
                .flatMap(user -> bidRepository.findByTrackingNumber(trackingNumber)
                        .flatMap(bid -> accessible(user.getId(), bid)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ParcelRef> byId(String firebaseUid, UUID bidId) {
        if (bidId == null) return Optional.empty();
        return userRepository.findByFirebaseUid(firebaseUid)
                .flatMap(user -> bidRepository.findById(bidId).flatMap(bid -> accessible(user.getId(), bid)));
    }

    private Optional<ParcelRef> accessible(UUID userId, BidEntity bid) {
        if (BidStatus.NEGOTIATION_STATUSES.contains(bid.getStatus())) return Optional.empty();
        AnnouncementEntity announcement = bid.getAnnouncementId() == null ? null
                : announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        ParcelRole role = null;
        if (userId.equals(bid.getSenderId()) && !bid.isDeletedBySender()) {
            role = ParcelRole.SENDER;
        } else if (announcement != null && userId.equals(announcement.getTravelerId()) && !bid.isDeletedByTraveler()) {
            role = ParcelRole.TRAVELER;
        } else if (linkRepository.existsByBidIdAndRecipientUserIdAndStatus(bid.getId(), userId,
                ReceptionLinkStatus.CONFIRMED)) {
            role = ParcelRole.RECIPIENT;
        }
        return role == null ? Optional.empty() : Optional.of(toRef(bid, userId, role, announcement));
    }

    private static ParcelRef toRef(BidEntity bid, UUID userId, ParcelRole role, AnnouncementEntity announcement) {
        boolean numberVisible = role != ParcelRole.TRAVELER
                || BidService.TRACKING_NUMBER_VISIBLE_TO_TRAVELER_STATUSES.contains(bid.getStatus());
        return new ParcelRef(bid.getId(), userId, role, bid.getStatus().name(),
                numberVisible ? bid.getTrackingNumber() : null,
                announcement != null ? announcement.getDepartureCity() : null,
                announcement != null ? announcement.getArrivalCity() : null,
                announcement != null ? announcement.getDepartureDate() : null,
                ACTIVE.contains(bid.getStatus()));
    }

    private record Candidate(BidEntity bid, ParcelRole role) {
    }
}
