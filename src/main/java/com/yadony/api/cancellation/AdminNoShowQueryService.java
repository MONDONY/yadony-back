package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.AdminNoShowResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lecture de la file admin des no-shows ({@code GET /admin/cancellations}).
 *
 * <p>Ne liste que les déclarations de no-show ({@link NoShowReasons#ALL}), jamais les autres
 * annulations. Une page = une requête par table (bids, annonces, comptes, paiements,
 * litiges), jamais une par ligne. Lecture seule des dépôts des autres packages, comme
 * {@link CancellationService} : aucun service d'un autre package n'est injecté.
 */
@Service
@Transactional(readOnly = true)
public class AdminNoShowQueryService {

    static final int MAX_PAGE_SIZE = 100;
    private static final String ALL = "ALL";

    private final CancellationRepository cancellationRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final DisputeRepository disputeRepository;

    public AdminNoShowQueryService(CancellationRepository cancellationRepository,
                                   BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   UserRepository userRepository,
                                   PaymentRepository paymentRepository,
                                   DisputeRepository disputeRepository) {
        this.cancellationRepository = cancellationRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
    }

    /**
     * @param status     PENDING_CONFIRMATION (défaut), CONTESTED, CONFIRMED, RESOLVED ou ALL
     * @param scope      HANDOVER, DELIVERY ou ALL (défaut)
     * @param canResolve l'appelant détient DISPUTE_RESOLVE (pilote canConfirm / canReject)
     */
    public Page<AdminNoShowResponse> list(String status, String scope, int page, int size, boolean canResolve) {
        Set<CancellationStatus> statuses = parseStatuses(status);
        Set<CancellationScope> scopes = parseScopes(scope);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<CancellationEntity> rows = cancellationRepository.findAdminNoShows(NoShowReasons.ALL, scopes, statuses,
                PageRequest.of(Math.max(page, 0), pageSize, Sort.by("createdAt").descending()));
        Context ctx = load(rows.getContent());
        OffsetDateTime now = OffsetDateTime.now();
        return rows.map(c -> toResponse(c, ctx, canResolve, now));
    }

    /** Une seule ligne, après une décision : même forme que la liste. */
    public AdminNoShowResponse describe(CancellationEntity c, boolean canResolve) {
        return toResponse(c, load(List.of(c)), canResolve, OffsetDateTime.now());
    }

    // ── Filtres ──────────────────────────────────────────────────────────────

    private static Set<CancellationStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) return EnumSet.of(CancellationStatus.PENDING_CONFIRMATION);
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (ALL.equals(value)) return EnumSet.allOf(CancellationStatus.class);
        try {
            return EnumSet.of(CancellationStatus.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw invalidFilter("status", raw);
        }
    }

    private static Set<CancellationScope> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) return EnumSet.allOf(CancellationScope.class);
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (ALL.equals(value)) return EnumSet.allOf(CancellationScope.class);
        try {
            return EnumSet.of(CancellationScope.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw invalidFilter("scope", raw);
        }
    }

    private static YadonyBusinessException invalidFilter(String name, String value) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-noshow-filter",
                "Invalid Filter", "Valeur de filtre inconnue pour " + name + " : " + value);
    }

    // ── Chargement groupé ────────────────────────────────────────────────────

    private record Context(Map<UUID, BidEntity> bids, Map<UUID, AnnouncementEntity> announcements,
                           Map<UUID, UserEntity> users, Map<UUID, PaymentEntity> payments,
                           Map<UUID, List<DisputeEntity>> disputes) {
    }

    private Context load(List<CancellationEntity> rows) {
        if (rows.isEmpty()) return new Context(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        Set<UUID> bidIds = rows.stream().map(CancellationEntity::getBidId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, BidEntity> bids = byId(bidRepository.findAllById(bidIds), BidEntity::getId);

        Set<UUID> announcementIds = bids.values().stream().map(BidEntity::getAnnouncementId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AnnouncementEntity> announcements = announcementIds.isEmpty() ? Map.of()
                : byId(announcementRepository.findAllById(announcementIds), AnnouncementEntity::getId);

        Set<UUID> userIds = new HashSet<>();
        rows.forEach(c -> userIds.add(c.getCancelledBy()));
        bids.values().forEach(b -> userIds.add(b.getSenderId()));
        announcements.values().forEach(a -> userIds.add(a.getTravelerId()));
        userIds.remove(null);
        Map<UUID, UserEntity> users = userIds.isEmpty() ? Map.of()
                : byId(userRepository.findAllById(userIds), UserEntity::getId);

        Map<UUID, PaymentEntity> payments = byId(paymentRepository.findByBidIdIn(bidIds), PaymentEntity::getBidId);
        Map<UUID, List<DisputeEntity>> disputes = disputeRepository.findByBidIdIn(bidIds).stream()
                .filter(d -> d.getBidId() != null)
                .collect(Collectors.groupingBy(DisputeEntity::getBidId));
        return new Context(bids, announcements, users, payments, disputes);
    }

    private static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> key) {
        return items.stream().filter(i -> key.apply(i) != null)
                .collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
    }

    // ── Assemblage d'une ligne ───────────────────────────────────────────────

    private AdminNoShowResponse toResponse(CancellationEntity c, Context ctx, boolean canResolve,
                                           OffsetDateTime now) {
        BidEntity bid = ctx.bids().get(c.getBidId());
        AnnouncementEntity ann = bid != null ? ctx.announcements().get(bid.getAnnouncementId()) : null;
        PaymentEntity payment = ctx.payments().get(c.getBidId());
        String status = c.getNoShowStatus() != null ? c.getNoShowStatus().name() : null;
        boolean open = c.getNoShowStatus() == CancellationStatus.PENDING_CONFIRMATION
                || c.getNoShowStatus() == CancellationStatus.CONTESTED;

        String declarantRole = NoShowReasons.declarantRole(c.getReason());
        AdminNoShowResponse.Party declarant = new AdminNoShowResponse.Party(
                c.getCancelledBy(), userName(ctx, c.getCancelledBy()), declarantRole);

        return new AdminNoShowResponse(
                c.getId(), c.getBidId(), c.getScope() != null ? c.getScope().name() : null, c.getReason(),
                status, status, c.getCancelledBy(), c.getContestationDeadline(), remainingMinutes(c, now),
                c.getCreatedAt(), declarant, accused(c, bid, ann, ctx),
                ann != null ? new AdminNoShowResponse.Trip(ann.getDepartureCity(), ann.getArrivalCity(),
                        ann.getDepartureDate()) : null,
                handoverAt(bid, ann),
                payment != null ? payment.getAmount() : (bid != null ? bid.getNegotiatedGrossEur() : null),
                currency(payment, bid),
                bid != null && bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : null,
                paymentStatus(payment, bid),
                bid != null && bid.getCommissionStatus() != null ? bid.getCommissionStatus().name() : null,
                bid != null && bid.getStatus() != null ? bid.getStatus().name() : null,
                dispute(c, ctx),
                canResolve && open, canResolve && open,
                c.getAdminDecision() != null ? c.getAdminDecision().name() : null,
                c.getDecidedAt(), c.getDecisionReason());
    }

    private static Long remainingMinutes(CancellationEntity c, OffsetDateTime now) {
        if (c.getNoShowStatus() != CancellationStatus.PENDING_CONFIRMATION || c.getContestationDeadline() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(now, c.getContestationDeadline()).toMinutes());
    }

    private AdminNoShowResponse.Party accused(CancellationEntity c, BidEntity bid, AnnouncementEntity ann,
                                              Context ctx) {
        String role = NoShowReasons.accusedRole(c.getReason());
        UUID userId = switch (role) {
            case "SENDER" -> bid != null ? bid.getSenderId() : null;
            case "TRAVELER" -> ann != null ? ann.getTravelerId() : null;
            default -> null; // RECIPIENT : le destinataire n'a en général pas de compte
        };
        String name = "RECIPIENT".equals(role)
                ? (bid != null ? shortName(bid.getRecipientName()) : null)
                : userName(ctx, userId);
        return new AdminNoShowResponse.Party(userId, name, role);
    }

    private static String userName(Context ctx, UUID userId) {
        UserEntity u = userId != null ? ctx.users().get(userId) : null;
        return u != null ? u.publicDisplayName() : null;
    }

    /** « Awa Diop Ndiaye » → « Awa N. » : prénom et initiale du dernier nom, comme publicDisplayName. */
    static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        return parts[0] + " " + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + ".";
    }

    private static java.time.LocalDateTime handoverAt(BidEntity bid, AnnouncementEntity ann) {
        if (bid != null && bid.getHandoverDeadline() != null) return bid.getHandoverDeadline();
        return ann != null ? ann.getHandoverDeadline() : null;
    }

    private static String currency(PaymentEntity payment, BidEntity bid) {
        String raw = payment != null ? payment.getCurrency() : (bid != null ? bid.getCurrency() : null);
        return raw != null ? raw.toUpperCase(Locale.ROOT) : null;
    }

    /** Statut du paiement ; pour une remise en espèces (pas de paiement), statut de la commission. */
    private static String paymentStatus(PaymentEntity payment, BidEntity bid) {
        if (payment != null && payment.getStatus() != null) return payment.getStatus().name();
        if (bid != null && bid.getPaymentMethod() == PaymentMethod.CASH && bid.getCommissionStatus() != null) {
            return bid.getCommissionStatus().name();
        }
        return null;
    }

    /** Litige lié à CETTE déclaration (types de sa portée), l'ouvert en priorité puis le plus récent. */
    private static AdminNoShowResponse.DisputeRef dispute(CancellationEntity c, Context ctx) {
        List<String> types = NoShowReasons.linkedDisputeTypes(c.getReason());
        return ctx.disputes().getOrDefault(c.getBidId(), List.of()).stream()
                .filter(d -> types.contains(d.getType()))
                .min(Comparator.comparing((DisputeEntity d) -> !"OPEN".equals(d.getStatus()))
                        .thenComparing(DisputeEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(d -> new AdminNoShowResponse.DisputeRef(d.getId(), d.getStatus()))
                .orElse(null);
    }
}
