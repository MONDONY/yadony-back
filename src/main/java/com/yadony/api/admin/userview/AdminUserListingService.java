package com.yadony.api.admin.userview;

import com.yadony.api.admin.dto.AdminAnnouncementListItemResponse;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.MoneyAmount;
import com.yadony.api.admin.userview.dto.AdminUserParcelRow;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
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

    public AdminUserListingService(BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   BidGridItemRepository gridItemRepository) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.gridItemRepository = gridItemRepository;
    }

    public Page<AdminUserParcelRow> parcels(UUID userId, int page, int size) {
        Page<BidEntity> bids = bidRepository.findAdminByParty(userId, pageable(page, size));
        List<AdminUserParcelRow> rows = toRows(bids.getContent(), userId);
        return new org.springframework.data.domain.PageImpl<>(rows, bids.getPageable(), bids.getTotalElements());
    }

    public Page<AdminAnnouncementListItemResponse> trips(UUID userId, int page, int size) {
        return announcementRepository.findByTravelerId(userId, pageable(page, size,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .map(a -> AdminAnnouncementListItemResponse.of(a, null, null));
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

    /** Net voyageur du colis (accord négocié, sinon barème), dans la devise du colis ; null si inconnu. */
    private static MoneyAmount amountOf(BidEntity b, AnnouncementEntity a, BigDecimal gridNet) {
        BigDecimal net = b.getNegotiatedNetEur();
        if (net == null) {
            BigDecimal kg = (b.getWeightKg() != null && a != null && a.getPricePerKg() != null)
                    ? b.getWeightKg().multiply(a.getPricePerKg()) : BigDecimal.ZERO;
            BigDecimal total = kg.add(gridNet != null ? gridNet : BigDecimal.ZERO);
            net = total.signum() > 0 ? total : null;
        }
        if (net == null) return null;
        String currency = b.getCurrency() != null ? b.getCurrency().toUpperCase(Locale.ROOT) : null;
        return new MoneyAmount(currency, net.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private Map<UUID, BigDecimal> gridNetByBid(Collection<UUID> bidIds) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (BidGridItemEntity item : gridItemRepository.findByBidIdIn(bidIds)) {
            BigDecimal line = item.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(item.getQuantity()));
            totals.merge(item.getBidId(), line, BigDecimal::add);
        }
        return totals;
    }

    private static Pageable pageable(int page, int size) {
        return pageable(page, size, org.springframework.data.domain.Sort.unsorted());
    }

    private static Pageable pageable(int page, int size, org.springframework.data.domain.Sort sort) {
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safeSize, sort);
    }
}
