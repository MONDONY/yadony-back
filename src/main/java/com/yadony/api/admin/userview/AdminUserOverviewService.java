package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.Kpis;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.InProgressItem;
import com.yadony.api.admin.userview.dto.AdminUserParcelRow;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Assemble la synthèse d'un utilisateur ; chaque domaine n'est calculé que si l'appelant y a droit. */
@Service
public class AdminUserOverviewService {

    /** Un colis livré reste « en cours » dans la synthèse pendant cette fenêtre (UTC). */
    public static final Duration RECENT_DELIVERY_WINDOW = Duration.ofDays(3);

    /** Colis « en cours ». */
    public static final Set<BidStatus> IN_PROGRESS_PARCEL_STATUSES = EnumSet.of(
            BidStatus.AWAITING_PAYMENT, BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
            BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

    /** Trajets « actifs ». */
    public static final Set<AnnouncementStatus> ACTIVE_TRIP_STATUSES = EnumSet.of(
            AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL, AnnouncementStatus.IN_PROGRESS);

    /** Nombre maximal d'éléments de la liste « en cours ». */
    public static final int IN_PROGRESS_LIMIT = 50;

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final AdminUserListingService listingService;
    private Clock clock = Clock.systemUTC();

    public AdminUserOverviewService(BidRepository bidRepository,
                                    AnnouncementRepository announcementRepository,
                                    AdminUserListingService listingService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.listingService = listingService;
    }

    /** Pour les tests : fixe « maintenant ». */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminUserOverviewResponse overview(UserEntity user, Set<String> authorities) {
        boolean bids = has(authorities, AdminPermission.BID_VIEW);
        boolean payments = has(authorities, AdminPermission.PAYMENT_VIEW);
        boolean disputes = has(authorities, AdminPermission.DISPUTE_VIEW);
        boolean reports = has(authorities, AdminPermission.REPORT_VIEW);

        UUID userId = user.getId();
        Kpis kpis = new Kpis(
                bids ? (int) bidRepository.countAdminByPartyAndStatusIn(userId, IN_PROGRESS_PARCEL_STATUSES) : null,
                bids ? (int) announcementRepository.countByTravelerIdAndStatusIn(userId, ACTIVE_TRIP_STATUSES) : null,
                payments ? List.of() : null,
                disputes ? 0 : null,
                disputes ? 0 : null,
                reports ? 0 : null,
                reports ? 0 : null,
                reports ? 0 : null,
                user.getAverageRating() == null ? null : user.getAverageRating().doubleValue(),
                user.getRatingCount());
        return new AdminUserOverviewResponse(kpis, bids ? inProgress(userId) : null, List.of());
    }

    /** Colis en cours et livrés depuis moins de 72 h, plus trajets actifs ou terminés récemment. */
    private List<InProgressItem> inProgress(UUID userId) {
        LocalDateTime since = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).minus(RECENT_DELIVERY_WINDOW);
        Pageable limit = PageRequest.of(0, IN_PROGRESS_LIMIT);
        List<BidEntity> bids = bidRepository.findAdminInProgressByParty(
                userId, IN_PROGRESS_PARCEL_STATUSES, since, limit);
        Map<UUID, AdminUserParcelRow> rows = listingService.toRows(bids, userId).stream()
                .collect(Collectors.toMap(AdminUserParcelRow::id, r -> r));
        List<InProgressItem> items = new ArrayList<>();
        for (BidEntity b : bids) {
            AdminUserParcelRow r = rows.get(b.getId());
            items.add(new InProgressItem("PARCEL", b.getId(), r.trackingNumber(), r.corridor(), r.role(),
                    r.status(), AdminUserListingService.toInstant(b.getUpdatedAt()), r.amount()));
        }
        for (AnnouncementEntity a : announcementRepository.findAdminRecentByTraveler(
                userId, ACTIVE_TRIP_STATUSES, since, limit)) {
            items.add(new InProgressItem("TRIP", a.getId(), null,
                    MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity()), "TRAVELER",
                    a.getStatus().name(), AdminUserListingService.toInstant(a.getUpdatedAt()), null));
        }
        items.sort(Comparator.comparing(InProgressItem::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items.size() > IN_PROGRESS_LIMIT ? List.copyOf(items.subList(0, IN_PROGRESS_LIMIT)) : items;
    }

    private static boolean has(Set<String> authorities, AdminPermission permission) {
        return authorities.contains(permission.name());
    }
}
