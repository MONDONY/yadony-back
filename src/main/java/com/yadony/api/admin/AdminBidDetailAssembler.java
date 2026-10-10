package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminBidDetailResponse;
import com.yadony.api.admin.dto.AdminBidTimelineResponse;
import com.yadony.api.admin.dto.AdminWalletResponse;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.common.StorageService;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidPhotoService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lecture back-office d'un colis : tout ce que la fiche admin affiche autour du colis (trajet,
 * personnes, argent, liens) et sa chronologie complète. Lecture seule, aucun geste ici.
 *
 * <p>La chronologie mêlait jusqu'ici les seuls scans de suivi : un colis accepté mais pas encore
 * remis n'en a aucun, et la fiche affichait une chronologie vide. Elle réunit désormais les
 * scans, le journal d'audit du colis et des entités qui en dépendent (scans, code de remise,
 * litige, annulation, notations, conversation), la vie du paiement, et les dates portées par
 * ces entités quand aucune trace d'audit ne les raconte (données antérieures à l'audit).
 */
@Component
public class AdminBidDetailAssembler {

    private static final Logger log = LoggerFactory.getLogger(AdminBidDetailAssembler.class);

    /** Durée de vie des URL signées vues par l'admin (photos du colis, photos de scan). */
    static final Duration PHOTO_TTL = Duration.ofMinutes(15);

    /** Types d'audit dont l'entité est le colis lui-même. */
    private static final List<String> BID_ENTITY_TYPES =
            List.of("BID", "TRACKING_CONFIRMATION_CODE", "RECETTE", "payment");

    private final BidRepository bidRepo;
    private final TrackingEventRepository trackingRepo;
    private final UserRepository userRepo;
    private final PaymentRepository paymentRepo;
    private final DisputeRepository disputeRepo;
    private final CancellationRepository cancellationRepo;
    private final RatingRepository ratingRepo;
    private final ConversationRepository conversationRepo;
    private final AuditLogRepository auditRepo;
    private final AdminPaymentTimeline paymentTimeline;
    private final BidPhotoService photoService;
    private final StorageService storageService;
    private final FirebaseContactService contactService;

    public AdminBidDetailAssembler(BidRepository bidRepo, TrackingEventRepository trackingRepo,
                                   UserRepository userRepo, PaymentRepository paymentRepo,
                                   DisputeRepository disputeRepo, CancellationRepository cancellationRepo,
                                   RatingRepository ratingRepo, ConversationRepository conversationRepo,
                                   AuditLogRepository auditRepo, AdminPaymentTimeline paymentTimeline,
                                   BidPhotoService photoService, StorageService storageService,
                                   FirebaseContactService contactService) {
        this.bidRepo = bidRepo;
        this.trackingRepo = trackingRepo;
        this.userRepo = userRepo;
        this.paymentRepo = paymentRepo;
        this.disputeRepo = disputeRepo;
        this.cancellationRepo = cancellationRepo;
        this.ratingRepo = ratingRepo;
        this.conversationRepo = conversationRepo;
        this.auditRepo = auditRepo;
        this.paymentTimeline = paymentTimeline;
        this.photoService = photoService;
        this.storageService = storageService;
        this.contactService = contactService;
    }

    /**
     * Ce que l'admin a le droit de voir au-delà de BID_VIEW : les mêmes autorités fines que les
     * fiches dédiées (paiement : PAYMENT_VIEW, utilisateur : USER_VIEW, litige : DISPUTE_VIEW,
     * conversation : MODERATION_VIEW, auteurs admin : PAYMENT_VIEW ou AUDIT_VIEW). Les
     * surcharges de permissions peuvent retirer l'une d'elles à un rôle qui garde BID_VIEW.
     */
    public record Access(boolean payments, boolean users, boolean disputes, boolean moderation, boolean adminActors) {
        public static final Access ALL = new Access(true, true, true, true, true);

        public static Access of(Authentication auth) {
            if (auth == null) return new Access(false, false, false, false, false);
            Set<String> a = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            return new Access(a.contains("PAYMENT_VIEW"), a.contains("USER_VIEW"), a.contains("DISPUTE_VIEW"),
                    a.contains("MODERATION_VIEW"), a.contains("PAYMENT_VIEW") || a.contains("AUDIT_VIEW"));
        }
    }

    /** Contexte du colis, à ajouter en fin de {@link AdminBidDetailResponse}. */
    public record Extras(
            AdminBidDetailResponse.Trip trip,
            AdminBidDetailResponse.Party sender,
            AdminBidDetailResponse.Party traveler,
            AdminBidDetailResponse.Recipient recipient,
            AdminBidDetailResponse.Money money,
            AdminBidDetailResponse.Links links,
            Boolean confirmationCodePresent,
            List<String> photoUrls,
            AdminBidDetailResponse.Milestones milestones) {
    }

    public Extras extras(BidEntity bid, AnnouncementEntity ann, Access access) {
        UUID senderId = bid.getSenderId();
        UUID travelerId = ann != null ? ann.getTravelerId() : null;
        Map<UUID, UserEntity> users = usersOf(senderId, travelerId);
        // Téléphones : lus chez Firebase seulement pour qui peut voir les fiches utilisateur.
        Map<String, FirebaseContactService.Contact> contacts = access.users() ? contactsOf(users.values()) : Map.of();

        PaymentEntity payment = access.payments() ? paymentOf(bid).orElse(null) : null;
        DisputeEntity dispute = access.disputes() ? latest(disputeRepo.findByBidIdIn(List.of(bid.getId()))) : null;
        CancellationEntity cancellation = latest(cancellationsOf(bid.getId()));
        String conversationId = access.moderation()
                ? conversationOf(bid.getId()).map(ConversationEntity::getFirestoreConversationId).orElse(null)
                : null;

        return new Extras(
                trip(bid, ann),
                party(users.get(senderId), contacts, false, access.users()),
                party(users.get(travelerId), contacts, true, access.users()),
                bid.getRecipientName() == null && bid.getRecipientPhone() == null ? null
                        : new AdminBidDetailResponse.Recipient(bid.getRecipientName(),
                                access.users() ? maskPhone(bid.getRecipientPhone()) : null),
                money(payment),
                new AdminBidDetailResponse.Links(
                        bid.getLinkedNegotiationThreadId() != null ? bid.getLinkedNegotiationThreadId()
                                : payment != null ? payment.getNegotiationThreadId() : null,
                        dispute != null ? dispute.getId() : null,
                        dispute != null ? dispute.getStatus() : null,
                        conversationId,
                        cancellation != null ? cancellation.getId() : null),
                bid.getConfirmationCode() != null && !bid.getConfirmationCode().isBlank(),
                photoUrls(bid.getId()),
                new AdminBidDetailResponse.Milestones(
                        bid.getHandoverLocation(), bid.getHandoverDeadline(), bid.getArrivedAt(),
                        bid.getDeliveredAt(), bid.getNoShowAt(), bid.getReturnedAt()));
    }

    // ── Chronologie ─────────────────────────────────────────────────────────────

    public List<AdminBidTimelineResponse.Entry> timeline(BidEntity bid, Access access) {
        UUID bidId = bid.getId();
        List<AdminBidTimelineResponse.Entry> entries = new ArrayList<>();

        List<TrackingEventEntity> scans = trackingRepo.findByBidIdOrderByScannedAtAsc(bidId);
        List<DisputeEntity> disputes = disputeRepo.findByBidIdIn(List.of(bidId));
        List<CancellationEntity> cancellations = cancellationsOf(bidId);
        List<RatingEntity> ratings = ratingRepo.findByBidId(bidId);
        Optional<String> conversationEntityId = conversationOf(bidId)
                .map(c -> c.getId() != null ? c.getId().toString() : null);

        // Journal d'audit du colis et de ce qui en dépend : chaque type avec ses propres identifiants.
        Map<String, Set<UUID>> idsByType = new HashMap<>();
        BID_ENTITY_TYPES.forEach(t -> idsByType.put(t, Set.of(bidId)));
        // Commissions (type « payment ») : de l'argent, réservées comme le paiement à PAYMENT_VIEW.
        if (!access.payments()) idsByType.remove("payment");
        // TRACKING_EVENT (SCAN_DEPART…) doublonne les scans déjà listés : seule la confirmation
        // de livraison, qui n'a pas d'équivalent parmi eux, est reprise.
        idsByType.put("TRACKING_DELIVERY_CONFIRMED", ids(scans.stream().map(TrackingEventEntity::getId).toList()));
        idsByType.put("DISPUTE", ids(disputes.stream().map(DisputeEntity::getId).toList()));
        idsByType.put("CANCELLATION", ids(cancellations.stream().map(CancellationEntity::getId).toList()));
        idsByType.put("RATING", ids(ratings.stream().map(RatingEntity::getId).toList()));
        conversationEntityId.ifPresent(id -> idsByType.put("conversation", Set.of(UUID.fromString(id))));
        List<AuditLogEntity> audit = audit(idsByType);
        Set<UUID> auditedEntities = audit.stream().map(AuditLogEntity::getEntityId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> auditedBidActions = audit.stream().filter(a -> bidId.equals(a.getEntityId()))
                .map(AuditLogEntity::getAction).collect(Collectors.toSet());

        // Création : la date du colis quand l'audit ne la raconte pas.
        if (!auditedBidActions.contains("BID_CREATED") && !auditedBidActions.contains("CREATED_FROM_THREAD")) {
            entries.add(entry(bid.getCreatedAt(), "EVENT", "BID_CREATED", null, "BID"));
        }

        for (TrackingEventEntity e : scans) {
            entries.add(new AdminBidTimelineResponse.Entry(
                    e.getScannedAt(), "SCAN",
                    e.getEventType() != null ? e.getEventType().name() : "SCAN",
                    e.getGpsLabel(), signed(e.getPhotoUrl()), e.getGpsLat(), e.getGpsLon(),
                    "TRACKING", null, null));
        }

        // Auteurs : admins par leur e-mail, utilisateurs par leur nom.
        Set<UUID> actorIds = new LinkedHashSet<>();
        audit.forEach(a -> { if (a.getActorId() != null) actorIds.add(a.getActorId()); });
        PaymentEntity payment = access.payments() ? paymentOf(bid).orElse(null) : null;
        List<AdminPaymentTimeline.Entry> paymentEntries = payment != null ? paymentTimeline.of(payment) : List.of();
        Map<UUID, String> admins = actorIds.isEmpty() ? Map.of() : paymentTimeline.adminEmailsOf(actorIds);
        // Sans PAYMENT_VIEW ni AUDIT_VIEW : « admin » sans son e-mail.
        java.util.function.Function<UUID, String> adminLabel = id -> access.adminActors() ? admins.get(id) : null;
        Map<UUID, String> names = namesOf(actorIds.stream().filter(id -> !admins.containsKey(id)).toList());
        for (AuditLogEntity a : audit) {
            UUID actor = a.getActorId();
            String kind = actor == null ? null : admins.containsKey(actor) ? "ADMIN" : names.containsKey(actor) ? "USER" : null;
            String label = actor == null ? null : admins.containsKey(actor) ? adminLabel.apply(actor) : names.get(actor);
            String entryKind = "payment".equals(a.getEntityType()) ? "PAYMENT" : "EVENT";
            entries.add(new AdminBidTimelineResponse.Entry(a.getCreatedAt(), entryKind, a.getAction(), null, null,
                    null, null, "AUDIT", kind, label));
        }

        for (AdminPaymentTimeline.Entry p : paymentEntries) {
            entries.add(new AdminBidTimelineResponse.Entry(p.at(), "PAYMENT", p.action(), null, null, null, null,
                    "AUDIT".equals(p.source()) ? "AUDIT" : "PAYMENT", p.actorKind(), p.actorLabel()));
        }

        // Dates portées par les entités liées, quand leur audit est absent.
        for (DisputeEntity d : disputes) {
            if (auditedEntities.contains(d.getId())) continue;
            entries.add(entry(d.getCreatedAt(), "EVENT", "DISPUTE_OPENED", d.getType(), "BID"));
            if (d.getResolvedAt() != null) {
                entries.add(entry(utc(d.getResolvedAt()), "EVENT", "DISPUTE_RESOLVED", d.getResolutionType(), "BID"));
            }
        }
        for (CancellationEntity c : cancellations) {
            if (auditedEntities.contains(c.getId())) continue;
            entries.add(entry(c.getCreatedAt(), "EVENT", "CANCELLATION_CREATED",
                    c.getScope() != null ? c.getScope().name() : null, "BID"));
        }
        for (RatingEntity r : ratings) {
            if (auditedEntities.contains(r.getId())) continue;
            entries.add(entry(r.getCreatedAt(), "EVENT", "RATING_CREATED", r.getStars() + "/5", "BID"));
        }
        if (bid.getDeliveredAt() != null && scans.isEmpty() && !auditedBidActions.contains("DELIVERY_CONFIRMED")) {
            entries.add(entry(bid.getDeliveredAt(), "EVENT", "DELIVERED", null, "BID"));
        }

        entries.removeIf(e -> e.at() == null);
        entries.sort(Comparator.comparing(AdminBidTimelineResponse.Entry::at));
        return entries;
    }

    // ── Morceaux ────────────────────────────────────────────────────────────────

    private AdminBidDetailResponse.Trip trip(BidEntity bid, AnnouncementEntity ann) {
        if (ann == null) return null;
        long others = Math.max(0, bidRepo.countByAnnouncementId(ann.getId()) - 1);
        return new AdminBidDetailResponse.Trip(
                ann.getId(),
                ann.getStatus() != null ? ann.getStatus().name() : null,
                ann.getDepartureCity(), ann.getArrivalCity(),
                ann.getDepartureCountryCode(), ann.getArrivalCountryCode(),
                ann.getDepartureDate(), ann.getDepartureTime(), ann.getDepartureAt(),
                ann.getArrivalDate(), ann.getArrivalTime(), ann.getTimezone(),
                ann.getPickupAddressLabel(), ann.getDeliveryAddressLabel(),
                ann.getTransportMode() != null ? ann.getTransportMode().name() : null,
                ann.getTotalKg(), ann.getAvailableKg(), ann.getReservedKg(),
                ann.getCapacityUnit() != null ? ann.getCapacityUnit().name() : null,
                ann.getPricePerKg(),
                ann.getTripGroupId(), ann.getTripLegIndex(),
                bid.getHandoverDeadline() != null ? bid.getHandoverDeadline() : ann.getHandoverDeadline(),
                others);
    }

    private static AdminBidDetailResponse.Party party(UserEntity u, Map<String, FirebaseContactService.Contact> contacts,
                                                      boolean payee, boolean userDetails) {
        if (u == null) return null;
        if (!userDetails) {
            // Sans USER_VIEW : l'identité seule, comme le nom déjà présent dans la fiche colis.
            return new AdminBidDetailResponse.Party(u.getId(), MatchingTextUtil.buildName(u), u.getUsername(),
                    null, null, null, null, null, null, null);
        }
        FirebaseContactService.Contact contact = u.getFirebaseUid() != null
                ? contacts.getOrDefault(u.getFirebaseUid(), FirebaseContactService.Contact.EMPTY)
                : FirebaseContactService.Contact.EMPTY;
        return new AdminBidDetailResponse.Party(
                u.getId(), MatchingTextUtil.buildName(u), u.getUsername(), maskPhone(contact.phoneNumber()),
                u.getStatus() != null ? u.getStatus().name() : null,
                u.getKycStatus() != null ? u.getKycStatus().name() : null,
                payee && u.getStripeAccountStatus() != null ? u.getStripeAccountStatus().name() : null,
                payee ? u.hasActiveStripeConnect() : null,
                payee && u.getMobileMoneyStatus() != null ? u.getMobileMoneyStatus().name() : null,
                payee ? u.hasActiveMobileMoney() : null);
    }

    private static AdminBidDetailResponse.Money money(PaymentEntity p) {
        if (p == null) return null;
        return new AdminBidDetailResponse.Money(
                p.getId(),
                p.getStatus() != null ? p.getStatus().name() : null,
                p.getRail() != null ? p.getRail().name() : null,
                AdminWalletResponse.toCents(p.getAmount()),
                AdminWalletResponse.toCents(p.getCommissionAmount()),
                AdminWalletResponse.toCents(p.getRefundedAmount()),
                p.getCurrency() != null ? p.getCurrency().toUpperCase(Locale.ROOT) : null,
                p.getCapturedAt(), p.getEscrowReleasedAt(), p.getPayoutHeldAt(), p.isDisputed());
    }

    /** Annulations encore en vigueur (l'entité n'a pas de filtre de suppression logique). */
    private List<CancellationEntity> cancellationsOf(UUID bidId) {
        return cancellationRepo.findAllByBidId(bidId).stream().filter(c -> c.getDeletedAt() == null).toList();
    }

    /** Conversation expéditeur-voyageur non supprimée (l'entité n'a pas de filtre de suppression logique). */
    private Optional<ConversationEntity> conversationOf(UUID bidId) {
        return conversationRepo.findByBidId(bidId).filter(c -> c.getDeletedAt() == null);
    }

    /** Paiement direct du colis, sinon celui du fil de négociation dont il est issu. */
    private Optional<PaymentEntity> paymentOf(BidEntity bid) {
        Optional<PaymentEntity> direct = paymentRepo.findByBidId(bid.getId());
        if (direct.isPresent() || bid.getLinkedNegotiationThreadId() == null) return direct;
        return paymentRepo.findLinkedNegotiationPaymentOfBid(bid.getId());
    }

    private List<String> photoUrls(UUID bidId) {
        try {
            return photoService.activePhotos(bidId).stream().map(p -> p.url()).toList();
        } catch (RuntimeException e) {
            // Stockage indisponible : la fiche reste lisible, sans photos.
            log.warn("Photos du colis {} illisibles : {}", bidId, e.toString());
            return List.of();
        }
    }

    /** Photo de scan : la base garde une clé de stockage, jamais une URL publique. */
    private String signed(String key) {
        if (key == null || key.isBlank()) return null;
        if (key.startsWith("http")) return key;
        try {
            return storageService.generatePresignedUrl(key, PHOTO_TTL);
        } catch (RuntimeException e) {
            log.warn("Photo de scan illisible : {}", e.toString());
            return null;
        }
    }

    private List<AuditLogEntity> audit(Map<String, Set<UUID>> idsByType) {
        Set<UUID> all = new HashSet<>();
        idsByType.values().forEach(all::addAll);
        if (all.isEmpty()) return List.of();
        // Une seule requête : types × identifiants, puis on ne garde que les couples attendus.
        return auditRepo.findTop300ByEntityTypeInAndEntityIdInOrderByCreatedAtAscIdAsc(idsByType.keySet(), all)
                .stream()
                .filter(a -> idsByType.getOrDefault(a.getEntityType(), Set.of()).contains(a.getEntityId()))
                .toList();
    }

    private Map<UUID, UserEntity> usersOf(UUID... ids) {
        Set<UUID> wanted = new HashSet<>();
        for (UUID id : ids) if (id != null) wanted.add(id);
        if (wanted.isEmpty()) return Map.of();
        Map<UUID, UserEntity> users = new HashMap<>();
        userRepo.findAllById(wanted).forEach(u -> { if (u.getId() != null) users.put(u.getId(), u); });
        return users;
    }

    private Map<UUID, String> namesOf(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> names = new HashMap<>();
        userRepo.findAllById(ids).forEach(u -> { if (u.getId() != null) names.put(u.getId(), MatchingTextUtil.buildName(u)); });
        return names;
    }

    private Map<String, FirebaseContactService.Contact> contactsOf(java.util.Collection<UserEntity> users) {
        List<String> uids = users.stream().map(UserEntity::getFirebaseUid).filter(Objects::nonNull).toList();
        return uids.isEmpty() ? Map.of() : contactService.getContacts(uids);
    }

    private static Set<UUID> ids(List<UUID> list) {
        return list.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T extends com.yadony.api.common.BaseEntity> T latest(List<T> list) {
        return list.stream()
                .max(Comparator.comparing(com.yadony.api.common.BaseEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static AdminBidTimelineResponse.Entry entry(LocalDateTime at, String kind, String label, String detail,
                                                        String source) {
        return new AdminBidTimelineResponse.Entry(at, kind, label, detail, null, null, null, source, null, null);
    }

    private static LocalDateTime utc(OffsetDateTime at) {
        return at == null ? null : at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Seuls les quatre derniers chiffres restent lisibles. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? "••••" : "•••• " + digits.substring(digits.length() - 4);
    }
}
