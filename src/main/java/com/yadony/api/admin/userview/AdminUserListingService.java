package com.yadony.api.admin.userview;

import com.yadony.api.admin.AdminBidAmounts;
import com.yadony.api.admin.AdminPaymentInsights;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
import com.yadony.api.admin.dto.AdminWalletResponse;
import com.yadony.api.admin.userview.dto.AdminUserPaymentRow;
import com.yadony.api.admin.userview.dto.AdminUserCancellationRow;
import com.yadony.api.admin.userview.dto.AdminUserDisputeRow;
import com.yadony.api.admin.userview.dto.AdminUserRatingRow;
import com.yadony.api.admin.userview.dto.AdminUserReportRow;
import com.yadony.api.admin.userview.dto.AdminUserAuditRow;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.admin.userview.dto.AdminUserRequestRow;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.admin.dto.AdminAnnouncementListItemResponse;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.MoneyAmount;
import com.yadony.api.admin.userview.dto.AdminUserParcelRow;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Listes paginées de la fiche utilisateur (colis, trajets, ...), en lecture seule. */
@Service
@Transactional(readOnly = true)
public class AdminUserListingService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    /** Type d'entité sous lequel les gestes sur un compte sont tracés (auditService.log("USER", ...)). */
    static final String AUDIT_ENTITY_TYPE = "USER";

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final BidGridItemRepository gridItemRepository;
    private final AdminPaymentInsights paymentInsights;
    private final PayoutHoldPolicy holdPolicy;
    private final DisputeRepository disputeRepository;
    private final CancellationRepository cancellationRepository;
    private final ReportRepository reportRepository;
    private final RatingRepository ratingRepository;
    private final AuditLogRepository auditLogRepository;
    private final AdminUserRepository adminUserRepository;
    private final UserRepository userRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final NegotiationThreadRepository negotiationThreadRepository;

    public AdminUserListingService(BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   BidGridItemRepository gridItemRepository,
                                   AdminPaymentInsights paymentInsights,
                                   PayoutHoldPolicy holdPolicy,
                                   DisputeRepository disputeRepository,
                                   CancellationRepository cancellationRepository,
                                   ReportRepository reportRepository,
                                   RatingRepository ratingRepository,
                                   AuditLogRepository auditLogRepository,
                                   AdminUserRepository adminUserRepository,
                                   UserRepository userRepository,
                                   PackageRequestRepository packageRequestRepository,
                                   NegotiationThreadRepository negotiationThreadRepository) {
        this.auditLogRepository = auditLogRepository;
        this.adminUserRepository = adminUserRepository;
        this.userRepository = userRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.negotiationThreadRepository = negotiationThreadRepository;
        this.reportRepository = reportRepository;
        this.ratingRepository = ratingRepository;
        this.disputeRepository = disputeRepository;
        this.cancellationRepository = cancellationRepository;
        this.paymentInsights = paymentInsights;
        this.holdPolicy = holdPolicy;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.gridItemRepository = gridItemRepository;
    }

    public Page<AdminUserParcelRow> parcels(UUID userId, int page, int size) {
        Page<BidEntity> bids = bidRepository.findAdminByParty(userId, pageable(page, size));
        List<AdminUserParcelRow> rows = toRows(bids.getContent(), userId);
        return new PageImpl<>(rows, bids.getPageable(), bids.getTotalElements());
    }

    public Page<AdminAnnouncementListItemResponse> trips(UUID userId, int page, int size) {
        return announcementRepository.findByTravelerId(userId, pageable(page, size,
                        Sort.by(
                                Sort.Direction.DESC, "createdAt")))
                .map(a -> AdminAnnouncementListItemResponse.of(a, null, null));
    }

    /** Paiements dont l'utilisateur est payeur ou voyageur bénéficiaire. */
    public Page<AdminUserPaymentRow> payments(UUID userId, int page, int size) {
        Page<AdminPaymentListItemResponse> found = paymentInsights.searchByUser(userId, pageable(page, size));
        List<UUID> beneficiaries = found.getContent().stream()
                .map(AdminPaymentListItemResponse::travelerId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, PayoutHoldStatus> holds = beneficiaries.isEmpty() ? Map.of() : holdPolicy.statusesOf(beneficiaries);
        return found.map(p -> {
            PayoutHoldStatus hold = p.travelerId() == null ? null : holds.get(p.travelerId());
            boolean beneficiary = userId.equals(p.travelerId());
            return new AdminUserPaymentRow(p.id(), p.bidId(), p.status(), p.method(),
                    p.amountCents(), p.commissionCents(),
                    p.currency(), p.createdAt(), p.payoutHeldAt(), hold != null && hold.held(),
                    beneficiary ? "BENEFICIARY" : "PAYER");
        });
    }

    /** Litiges où l'utilisateur est expéditeur, voyageur ou déclarant, du plus récent au plus ancien. */
    public Page<AdminUserDisputeRow> disputes(UUID userId, int page, int size) {
        return disputeRepository.findAdminByParty(userId, pageable(page, size)).map(d -> new AdminUserDisputeRow(
                d.getId(), d.getBidId(), d.getType(), d.getStatus(), disputeRoleOf(d, userId),
                toInstant(d.getCreatedAt()),
                d.getResolvedAt() == null ? null : d.getResolvedAt().toInstant()));
    }

    /** Annulations et absences déclarées ou subies par l'utilisateur. */
    public Page<AdminUserCancellationRow> cancellations(UUID userId, int page, int size) {
        return cancellationRepository.findAdminByParty(userId, pageable(page, size)).map(c ->
                new AdminUserCancellationRow(c.getId(), c.getBidId(), c.getReason(), c.getScope().name(),
                        c.getNoShowStatus().name(), userId.equals(c.getCancelledBy()), toInstant(c.getCreatedAt())));
    }

    /** Signalements reçus (cible USER uniquement, périmètre v1) ou envoyés par l'utilisateur. */
    public Page<AdminUserReportRow> reports(UUID userId, boolean received, int page, int size) {
        Pageable pageable = pageable(page, size);
        Page<ReportEntity> found = received
                ? reportRepository.findAdminReceivedByUser(userId, pageable)
                : reportRepository.findAdminSentByUser(userId, pageable);
        String direction = received ? "RECEIVED" : "SENT";
        return found.map(r -> new AdminUserReportRow(r.getId(), direction, r.getTargetType().name(),
                r.getReason() == null ? null : r.getReason().name(), r.getStatus().name(),
                toInstant(r.getCreatedAt())));
    }

    /** Avis reçus par l'utilisateur, du plus récent au plus ancien. */
    public Page<AdminUserRatingRow> ratings(UUID userId, int page, int size) {
        return ratingRepository.findByRatedUserId(userId, pageable(page, size,
                        Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))))
                .map(r -> new AdminUserRatingRow(r.getId(), r.getBidId(), r.getStars(), r.getComment(),
                        r.isFlagged(), toInstant(r.getCreatedAt())));
    }

    /**
     * Demandes d'envoi publiées par l'utilisateur, de la plus récente à la plus ancienne (départage par
     * identifiant). Négociations ouvertes comptées en une seule requête pour toute la page.
     */
    public Page<AdminUserRequestRow> requests(UUID userId, int page, int size) {
        Page<PackageRequestEntity> found = packageRequestRepository
                .findBySenderIdOrderByCreatedAtDescIdDesc(userId, pageable(page, size));
        List<UUID> ids = found.getContent().stream().map(PackageRequestEntity::getId).toList();
        Map<UUID, Long> openCounts = ids.isEmpty() ? Map.of()
                : negotiationThreadRepository.findByPackageRequestIdIn(ids).stream()
                        .filter(t -> t.getStatus().isActive())
                        .collect(Collectors.groupingBy(NegotiationThreadEntity::getPackageRequestId,
                                Collectors.counting()));
        return found.map(r -> new AdminUserRequestRow(r.getId(),
                MatchingTextUtil.corridorLabel(r.getDepartureCity(), r.getArrivalCity()),
                r.getStatus().name(),
                r.getWeightKg() == null ? null : r.getWeightKg().doubleValue(),
                r.getDesiredDate() == null ? null : r.getDesiredDate().atStartOfDay().toInstant(ZoneOffset.UTC),
                toInstant(r.getCreatedAt()),
                openCounts.getOrDefault(r.getId(), 0L).intValue()));
    }

    /**
     * Gestes tracés sur l'entité {@value #AUDIT_ENTITY_TYPE} de l'utilisateur (suspension, bannissement,
     * commission, suppression...), du plus récent au plus ancien. Noms d'acteur résolus en deux requêtes
     * pour toute la page : les administrateurs vivent dans {@code admin_users} (nom = e-mail), l'utilisateur
     * lui-même (action automatique) dans {@code users}. Adresse IP jamais exposée.
     */
    public Page<AdminUserAuditRow> audit(UUID userId, int page, int size) {
        Page<AuditLogEntity> found = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(AUDIT_ENTITY_TYPE, userId, pageable(page, size));
        Set<UUID> actorIds = found.getContent().stream().map(AuditLogEntity::getActorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        if (!actorIds.isEmpty()) {
            for (UserEntity u : userRepository.findAllById(actorIds)) names.put(u.getId(), MatchingTextUtil.buildName(u));
            // Un administrateur l'emporte : son identifiant ne peut pas désigner un utilisateur, mais on tranche.
            for (AdminUserEntity a : adminUserRepository.findAllById(actorIds)) names.put(a.getId(), a.getEmail());
        }
        return found.map(e -> new AdminUserAuditRow(e.getId(), e.getAction(),
                e.getActorId() == null ? null : names.get(e.getActorId()), e.getActorId(),
                reasonOf(e.getPayload()), toInstant(e.getCreatedAt())));
    }

    private static String reasonOf(Map<String, Object> payload) {
        if (payload == null || !(payload.get("reason") instanceof String reason) || reason.isBlank()) return null;
        return reason;
    }

    static String disputeRoleOf(DisputeEntity d, UUID userId) {
        if (userId.equals(d.getSenderId())) return "SENDER";
        if (userId.equals(d.getTravelerId())) return "TRAVELER";
        return "REPORTER";
    }

    /** Lignes de colis ; annonces et articles de grille de toute la liste résolus en une requête chacun. */
    List<AdminUserParcelRow> toRows(List<BidEntity> bids, UUID userId) {
        if (bids.isEmpty()) return List.of();
        Map<UUID, AnnouncementEntity> announcements = announcementRepository
                .findAllById(bids.stream().map(BidEntity::getAnnouncementId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AnnouncementEntity::getId, Function.identity()));
        Map<UUID, BigDecimal> grid = gridNetByBid(bids.stream().map(BidEntity::getId).toList());
        return bids.stream().map(b -> {
            AnnouncementEntity a = announcements.get(b.getAnnouncementId());
            return new AdminUserParcelRow(
                    b.getId(), b.getTrackingNumber(),
                    a != null ? MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity()) : "",
                    roleOf(b, userId), b.getStatus().name(),
                    toInstant(b.getCreatedAt()), toInstant(b.getDeliveredAt()),
                    amountOf(b, a, grid.get(b.getId())), b.getAnnouncementId());
        }).toList();
    }

    static String roleOf(BidEntity b, UUID userId) {
        return userId.equals(b.getSenderId()) ? "SENDER" : "TRAVELER";
    }

    static Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    /** Net voyageur du colis en centièmes (montant x 100, toutes devises comme le reste de l'admin) ; null si inconnu. */
    private static MoneyAmount amountOf(BidEntity b, AnnouncementEntity a, BigDecimal gridNet) {
        BigDecimal net = AdminBidAmounts.netOf(b, a, gridNet);
        if (net == null) return null;
        String currency = b.getCurrency() != null ? b.getCurrency().toUpperCase(Locale.ROOT) : null;
        return new MoneyAmount(currency, AdminWalletResponse.toCents(net));
    }

    private Map<UUID, BigDecimal> gridNetByBid(Collection<UUID> bidIds) {
        return AdminBidAmounts.gridNetByBid(gridItemRepository.findByBidIdIn(bidIds));
    }

    private static Pageable pageable(int page, int size) {
        return pageable(page, size, Sort.unsorted());
    }

    private static Pageable pageable(int page, int size, Sort sort) {
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safeSize, sort);
    }
}
