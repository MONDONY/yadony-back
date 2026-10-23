package com.yadony.api.admin.userview;

import com.yadony.api.admin.AdminBidAmounts;
import com.yadony.api.admin.AdminPaymentInsights;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
import com.yadony.api.admin.dto.AdminWalletResponse;
import com.yadony.api.admin.userview.dto.AdminUserPaymentRow;
import com.yadony.api.admin.userview.dto.AdminUserCancellationRow;
import com.yadony.api.admin.userview.dto.AdminUserDisputeRow;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.admin.dto.AdminAnnouncementListItemResponse;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.MoneyAmount;
import com.yadony.api.admin.userview.dto.AdminUserParcelRow;
import com.yadony.api.common.MatchingTextUtil;
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

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final BidGridItemRepository gridItemRepository;
    private final AdminPaymentInsights paymentInsights;
    private final PayoutHoldPolicy holdPolicy;
    private final DisputeRepository disputeRepository;
    private final CancellationRepository cancellationRepository;

    public AdminUserListingService(BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   BidGridItemRepository gridItemRepository,
                                   AdminPaymentInsights paymentInsights,
                                   PayoutHoldPolicy holdPolicy,
                                   DisputeRepository disputeRepository,
                                   CancellationRepository cancellationRepository) {
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
