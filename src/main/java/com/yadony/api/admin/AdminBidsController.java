package com.yadony.api.admin;

import com.yadony.api.admin.dto.*;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.*;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('BID_VIEW')")
public class AdminBidsController {

    private final BidRepository bidRepo;
    private final AnnouncementRepository announcementRepo;
    private final TrackingEventRepository trackingRepo;
    private final UserRepository userRepo;
    private final com.yadony.api.matching.BidGridItemRepository bidGridItemRepo;

    public AdminBidsController(BidRepository bidRepo, AnnouncementRepository announcementRepo,
            TrackingEventRepository trackingRepo, UserRepository userRepo,
            com.yadony.api.matching.BidGridItemRepository bidGridItemRepo) {
        this.bidRepo = bidRepo;
        this.announcementRepo = announcementRepo;
        this.trackingRepo = trackingRepo;
        this.userRepo = userRepo;
        this.bidGridItemRepo = bidGridItemRepo;
    }

    @GetMapping("/admin/bids")
    public ResponseEntity<Page<AdminBidListItemResponse>> listBids(
            @RequestParam(required = false) BidStatus status,
            @RequestParam(required = false) UUID announcementId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<BidEntity> bidsPage = bidRepo.findAdminFiltered(
                status != null ? status.name() : null,
                announcementId != null ? announcementId.toString() : null,
                query,
                dateFrom,
                dateTo,
                PageRequest.of(page, size));

        // Batch load announcements to avoid N+1
        Set<UUID> annIds = bidsPage.stream()
                .map(BidEntity::getAnnouncementId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, AnnouncementEntity> annMap = announcementRepo.findAllById(annIds).stream()
                .collect(Collectors.toMap(a -> a.getId(), a -> a));

        // Batch load users (senders + travelers)
        Set<UUID> userIds = new HashSet<>();
        bidsPage.forEach(b -> {
            if (b.getSenderId() != null) userIds.add(b.getSenderId());
        });
        annMap.values().forEach(a -> {
            if (a.getTravelerId() != null) userIds.add(a.getTravelerId());
        });
        Map<UUID, String> userNames = loadUserNames(userIds);

        Map<UUID, java.math.BigDecimal> gridNetByBid = gridNetByBid(
                bidsPage.stream().map(BidEntity::getId).filter(java.util.Objects::nonNull).toList());
        Page<AdminBidListItemResponse> result = bidsPage.map(b ->
                toBidListItem(b, annMap.get(b.getAnnouncementId()), userNames,
                        b.getId() != null ? gridNetByBid.get(b.getId()) : null));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/admin/bids/{id}")
    public ResponseEntity<AdminBidDetailResponse> getBid(@PathVariable UUID id) {
        BidEntity bid = bidRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Colis introuvable"));

        AnnouncementEntity ann = bid.getAnnouncementId() != null
                ? announcementRepo.findById(bid.getAnnouncementId()).orElse(null) : null;

        Set<UUID> userIds = new HashSet<>();
        if (bid.getSenderId() != null) userIds.add(bid.getSenderId());
        if (ann != null && ann.getTravelerId() != null) userIds.add(ann.getTravelerId());
        Map<UUID, String> userNames = loadUserNames(userIds);

        return ResponseEntity.ok(toBidDetail(bid, ann, userNames));
    }

    @GetMapping("/admin/bids/{id}/timeline")
    public ResponseEntity<AdminBidTimelineResponse> getTimeline(@PathVariable UUID id) {
        BidEntity bid = bidRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Colis introuvable"));

        List<TrackingEventEntity> events = trackingRepo.findByBidIdOrderByScannedAtAsc(id);
        List<AdminBidTimelineResponse.Entry> entries = events.stream()
                .map(e -> new AdminBidTimelineResponse.Entry(
                        e.getScannedAt(),
                        "SCAN",
                        e.getEventType() != null ? e.getEventType().name() : "SCAN",
                        null,
                        e.getPhotoUrl(),
                        e.getGpsLat(),
                        e.getGpsLon()
                )).toList();

        return ResponseEntity.ok(new AdminBidTimelineResponse(id, entries));
    }

    @GetMapping("/admin/announcements")
    public ResponseEntity<Page<AdminAnnouncementListItemResponse>> listAnnouncements(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AnnouncementEntity> annPage = announcementRepo.findAll(
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        // Batch load traveler names
        Set<UUID> travelerIds = annPage.stream()
                .map(AnnouncementEntity::getTravelerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> userNames = loadUserNames(travelerIds);

        Page<AdminAnnouncementListItemResponse> result = annPage.map(a ->
                toAnnouncementListItem(a, userNames));
        return ResponseEntity.ok(result);
    }

    // --- mapping helpers ---

    /**
     * Net voyageur d'une demande : l'accord négocié quand il existe, sinon le barème
     * (poids × prix au kilo, plus les articles de la grille), dans la devise du bid.
     * Avant, une demande directe (jamais négociée) sortait sans net et le back-office
     * affichait un tiret.
     */
    static java.math.BigDecimal netOf(BidEntity b, AnnouncementEntity ann, java.math.BigDecimal gridNet) {
        if (b.getNegotiatedNetEur() != null) return b.getNegotiatedNetEur();
        java.math.BigDecimal kgNet = (b.getWeightKg() != null && ann != null && ann.getPricePerKg() != null)
                ? b.getWeightKg().multiply(ann.getPricePerKg())
                : java.math.BigDecimal.ZERO;
        java.math.BigDecimal total = kgNet.add(gridNet != null ? gridNet : java.math.BigDecimal.ZERO);
        return total.signum() > 0 ? total.setScale(2, java.math.RoundingMode.HALF_UP) : null;
    }

    /** Somme des articles de grille par bid, en une requête pour toute la page. */
    private Map<UUID, java.math.BigDecimal> gridNetByBid(java.util.Collection<UUID> bidIds) {
        if (bidIds.isEmpty()) return Map.of();
        Map<UUID, java.math.BigDecimal> totals = new java.util.HashMap<>();
        for (com.yadony.api.matching.BidGridItemEntity item : bidGridItemRepo.findByBidIdIn(bidIds)) {
            java.math.BigDecimal line = item.getUnitPriceNetSnapshot()
                    .multiply(java.math.BigDecimal.valueOf(item.getQuantity()));
            totals.merge(item.getBidId(), line, java.math.BigDecimal::add);
        }
        return totals;
    }

    private AdminBidListItemResponse toBidListItem(BidEntity b, AnnouncementEntity ann,
            Map<UUID, String> userNames, java.math.BigDecimal gridNet) {
        String senderName = b.getSenderId() != null ? userNames.get(b.getSenderId()) : null;
        String travelerName = ann != null && ann.getTravelerId() != null
                ? userNames.get(ann.getTravelerId()) : null;
        String corridor = ann != null
                ? MatchingTextUtil.corridorLabel(ann.getDepartureCity(), ann.getArrivalCity()) : "";
        String paymentMethod = b.getPaymentMethod() != null ? b.getPaymentMethod().name() : null;
        String commissionStatus = b.getCommissionStatus() != null ? b.getCommissionStatus().name() : null;
        String currency = b.getCurrency() != null ? b.getCurrency().toUpperCase(java.util.Locale.ROOT) : null;
        return new AdminBidListItemResponse(
                b.getId(), b.getStatus().name(), b.getAnnouncementId(),
                senderName, travelerName, corridor,
                b.getWeightKg(), netOf(b, ann, gridNet),
                paymentMethod, b.getCreatedAt(), commissionStatus, currency);
    }

    private AdminBidDetailResponse toBidDetail(BidEntity b, AnnouncementEntity ann,
            Map<UUID, String> userNames) {
        java.math.BigDecimal gridNet = b.getId() != null
                ? gridNetByBid(List.of(b.getId())).get(b.getId()) : null;
        AdminBidListItemResponse item = toBidListItem(b, ann, userNames, gridNet);
        return new AdminBidDetailResponse(
                item.id(), item.status(), item.announcementId(),
                item.senderName(), item.travelerName(), item.corridor(),
                item.weightKg(), item.netEur(), item.paymentMethod(), item.createdAt(),
                b.getContentCategory(), b.getRecipientName(),
                b.getTrackingNumber(), b.getCommissionRate(), b.getRefusalReason(),
                item.currency());
    }

    private AdminAnnouncementListItemResponse toAnnouncementListItem(AnnouncementEntity a,
            Map<UUID, String> userNames) {
        String travelerName = a.getTravelerId() != null ? userNames.get(a.getTravelerId()) : null;
        String corridor = MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity());
        return new AdminAnnouncementListItemResponse(
                a.getId(), a.getStatus().name(), travelerName,
                corridor, a.getDepartureDate(), a.getAvailableKg(), a.getPricePerKg(),
                a.getCurrency() != null ? a.getCurrency().toUpperCase(java.util.Locale.ROOT) : null);
    }

    private Map<UUID, String> loadUserNames(Set<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return userRepo.findAllById(userIds).stream()
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        MatchingTextUtil::buildName));
    }
}
