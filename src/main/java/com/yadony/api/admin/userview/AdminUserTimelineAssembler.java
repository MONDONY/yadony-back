package com.yadony.api.admin.userview;

import com.yadony.api.admin.AdminPaymentInsights;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.TimelineEntry;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import com.yadony.api.tracking.TrackingEventType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Frise « dernière activité » d'un utilisateur : colis, paiements reçus, litiges et signalements reçus,
 * fusionnés du plus récent au plus ancien. Une requête bornée par source, fusion en mémoire. Une source dont
 * la permission manque est ignorée : aucune donnée d'un domaine interdit n'apparaît.
 */
@Component
public class AdminUserTimelineAssembler {

    /** Nombre maximal d'événements gardés pour la fenêtre récente. */
    public static final int RECENT_CAP = 100;
    /** Taille minimale de la frise : complétée par les événements plus anciens si le compte est calme. */
    public static final int MIN_SIZE = 10;
    /** Lignes lues par source (au plus). */
    static final int SOURCE_LIMIT = 100;

    private static final Comparator<TimelineEntry> NEWEST_FIRST = Comparator
            .comparing(TimelineEntry::at, Comparator.reverseOrder())
            .thenComparing(TimelineEntry::kind)
            .thenComparing(e -> e.refId().toString())
            .thenComparing(TimelineEntry::title);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final AdminPaymentInsights paymentInsights;
    private final DisputeRepository disputeRepository;
    private final ReportRepository reportRepository;

    public AdminUserTimelineAssembler(BidRepository bidRepository,
                                      AnnouncementRepository announcementRepository,
                                      TrackingEventRepository trackingEventRepository,
                                      AdminPaymentInsights paymentInsights,
                                      DisputeRepository disputeRepository,
                                      ReportRepository reportRepository) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.paymentInsights = paymentInsights;
        this.disputeRepository = disputeRepository;
        this.reportRepository = reportRepository;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> build(UUID userId, Set<String> authorities, Instant now) {
        List<TimelineEntry> all = new ArrayList<>();
        if (has(authorities, AdminPermission.BID_VIEW)) all.addAll(parcelEvents(userId));
        if (has(authorities, AdminPermission.PAYMENT_VIEW)) all.addAll(paymentEvents(userId));
        if (has(authorities, AdminPermission.DISPUTE_VIEW)) all.addAll(disputeEvents(userId));
        if (has(authorities, AdminPermission.REPORT_VIEW)) all.addAll(reportEvents(userId));
        all.sort(NEWEST_FIRST);

        Instant since = now.minus(AdminUserOverviewService.RECENT_DELIVERY_WINDOW);
        int recent = (int) all.stream().filter(e -> !e.at().isBefore(since)).count();
        int size = recent >= MIN_SIZE ? Math.min(recent, RECENT_CAP) : Math.min(MIN_SIZE, all.size());
        return List.copyOf(all.subList(0, size));
    }

    private List<TimelineEntry> parcelEvents(UUID userId) {
        Pageable limit = PageRequest.of(0, SOURCE_LIMIT);
        List<BidEntity> bids = bidRepository.findAdminRecentActivityByParty(userId, limit);
        if (bids.isEmpty()) return List.of();
        Map<UUID, AnnouncementEntity> announcements = announcementRepository
                .findAllById(bids.stream().map(BidEntity::getAnnouncementId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AnnouncementEntity::getId, Function.identity()));
        Map<UUID, Instant> handovers = trackingEventRepository
                .findByBidIdInAndEventType(bids.stream().map(BidEntity::getId).toList(), TrackingEventType.DEPART)
                .stream().filter(e -> e.getScannedAt() != null)
                .collect(Collectors.toMap(TrackingEventEntity::getBidId,
                        e -> e.getScannedAt().toInstant(ZoneOffset.UTC), (a, b) -> a.isBefore(b) ? a : b));
        List<TimelineEntry> out = new ArrayList<>();
        for (BidEntity b : bids) {
            AnnouncementEntity a = announcements.get(b.getAnnouncementId());
            String corridor = a == null ? "" : MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity());
            String detail = parcelDetail(corridor, b.getTrackingNumber());
            add(out, "PARCEL", "Colis créé", detail, AdminUserListingService.toInstant(b.getCreatedAt()), "PARCEL", b.getId());
            add(out, "PARCEL", "Colis pris en charge", detail, handovers.get(b.getId()), "PARCEL", b.getId());
            add(out, "PARCEL", "Colis livré", detail, AdminUserListingService.toInstant(b.getDeliveredAt()), "PARCEL", b.getId());
        }
        return out;
    }

    private List<TimelineEntry> paymentEvents(UUID userId) {
        List<TimelineEntry> out = new ArrayList<>();
        for (AdminPaymentListItemResponse p : paymentInsights.searchByUser(userId, PageRequest.of(0, SOURCE_LIMIT))) {
            if (!userId.equals(p.travelerId())) continue; // frise : argent reçu en tant que voyageur
            String detail = formatAmount(p.amountCents(), p.currency());
            PaymentStatus status = PaymentStatus.valueOf(p.status());
            if (status == PaymentStatus.ESCROW || status == PaymentStatus.RELEASED) {
                add(out, "PAYMENT", "Paiement reçu", detail, AdminUserListingService.toInstant(p.createdAt()), "PAYMENT", p.id());
            }
            add(out, "PAYMENT", "Versement retenu", detail, AdminUserListingService.toInstant(p.payoutHeldAt()), "PAYMENT", p.id());
        }
        return out;
    }

    private List<TimelineEntry> disputeEvents(UUID userId) {
        List<TimelineEntry> out = new ArrayList<>();
        disputeRepository.findAdminByParty(userId, PageRequest.of(0, SOURCE_LIMIT)).forEach(d -> {
            String detail = d.getType();
            add(out, "DISPUTE", "Litige ouvert", detail, AdminUserListingService.toInstant(d.getCreatedAt()), "DISPUTE", d.getId());
            add(out, "DISPUTE", "Litige résolu", detail,
                    d.getResolvedAt() == null ? null : d.getResolvedAt().toInstant(), "DISPUTE", d.getId());
        });
        return out;
    }

    private List<TimelineEntry> reportEvents(UUID userId) {
        List<TimelineEntry> out = new ArrayList<>();
        reportRepository.findAdminReceivedByUser(userId, PageRequest.of(0, SOURCE_LIMIT)).forEach(r ->
                add(out, "REPORT", "Signalement reçu", r.getReason() == null ? null : r.getReason().label(),
                        AdminUserListingService.toInstant(r.getCreatedAt()), "REPORT", r.getId()));
        return out;
    }

    private static void add(List<TimelineEntry> out, String kind, String title, String detail, Instant at,
                            String refType, UUID refId) {
        if (at != null) out.add(new TimelineEntry(kind, title, detail, at, refType, refId));
    }

    private static String parcelDetail(String corridor, String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) return corridor;
        return corridor.isEmpty() ? trackingNumber : corridor + " · " + trackingNumber;
    }

    /** Montant en centièmes (x100 pour toutes les devises) écrit en unités majeures, devise à la suite. */
    static String formatAmount(long cents, String currency) {
        BigDecimal major = BigDecimal.valueOf(cents, 2);
        String text = cents % 100 == 0 ? major.setScale(0).toPlainString()
                : major.toPlainString().replace('.', ',');
        return currency == null ? text : text + " " + currency;
    }

    private static boolean has(Set<String> authorities, AdminPermission permission) {
        return authorities.contains(permission.name());
    }
}
