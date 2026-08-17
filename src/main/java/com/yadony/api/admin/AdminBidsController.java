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

    public AdminBidsController(BidRepository bidRepo, AnnouncementRepository announcementRepo,
            TrackingEventRepository trackingRepo, UserRepository userRepo) {
        this.bidRepo = bidRepo;
        this.announcementRepo = announcementRepo;
        this.trackingRepo = trackingRepo;
        this.userRepo = userRepo;
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

        Page<AdminBidListItemResponse> result = bidsPage.map(b ->
                toBidListItem(b, annMap.get(b.getAnnouncementId()), userNames));
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

    private AdminBidListItemResponse toBidListItem(BidEntity b, AnnouncementEntity ann,
            Map<UUID, String> userNames) {
        String senderName = b.getSenderId() != null ? userNames.get(b.getSenderId()) : null;
        String travelerName = ann != null && ann.getTravelerId() != null
                ? userNames.get(ann.getTravelerId()) : null;
        String corridor = ann != null
                ? MatchingTextUtil.corridorLabel(ann.getDepartureCity(), ann.getArrivalCity()) : "";
        String paymentMethod = b.getPaymentMethod() != null ? b.getPaymentMethod().name() : null;
        String commissionStatus = b.getCommissionStatus() != null ? b.getCommissionStatus().name() : null;
        return new AdminBidListItemResponse(
                b.getId(), b.getStatus().name(), b.getAnnouncementId(),
                senderName, travelerName, corridor,
                b.getWeightKg(), b.getNegotiatedNetEur(),
                paymentMethod, b.getCreatedAt(), commissionStatus);
    }

    private AdminBidDetailResponse toBidDetail(BidEntity b, AnnouncementEntity ann,
            Map<UUID, String> userNames) {
        AdminBidListItemResponse item = toBidListItem(b, ann, userNames);
        return new AdminBidDetailResponse(
                item.id(), item.status(), item.announcementId(),
                item.senderName(), item.travelerName(), item.corridor(),
                item.weightKg(), item.netEur(), item.paymentMethod(), item.createdAt(),
                b.getContentCategory(), b.getRecipientName(),
                b.getTrackingNumber(), b.getCommissionRate(), b.getRefusalReason());
    }

    private AdminAnnouncementListItemResponse toAnnouncementListItem(AnnouncementEntity a,
            Map<UUID, String> userNames) {
        String travelerName = a.getTravelerId() != null ? userNames.get(a.getTravelerId()) : null;
        String corridor = MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity());
        return new AdminAnnouncementListItemResponse(
                a.getId(), a.getStatus().name(), travelerName,
                corridor, a.getDepartureDate(), a.getAvailableKg(), a.getPricePerKg());
    }

    private Map<UUID, String> loadUserNames(Set<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return userRepo.findAllById(userIds).stream()
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        MatchingTextUtil::buildName));
    }
}
