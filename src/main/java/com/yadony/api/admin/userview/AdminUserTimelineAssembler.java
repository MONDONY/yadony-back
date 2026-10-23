package com.yadony.api.admin.userview;

import com.yadony.api.admin.AdminPaymentInsights;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.TimelineEntry;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.signalements.ReportEntity;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 *
 * <p>Décision (R6) : les paiements effectués en tant que payeur n'apparaissent PAS dans la frise. La spec
 * ne montre que les « paiements reçus / retenus », c'est-à-dire l'argent reçu comme voyageur bénéficiaire
 * et les versements retenus ; il n'y a donc volontairement aucun événement « Paiement effectué ».
 *
 * <p>Complétude : pour chaque source, une requête sur l'heure de l'ÉVÉNEMENT (borne {@code since}, au plus
 * {@value #RECENT_CAP} lignes) garantit que les 72 dernières heures sont complètes, même pour un vieil objet
 * dont un événement est récent ; une seconde requête, des {@value #MIN_SIZE} derniers objets, sert au
 * complément quand le compte est calme.
 */
@Component
public class AdminUserTimelineAssembler {

    /** Nombre maximal d'événements gardés pour la fenêtre récente. */
    public static final int RECENT_CAP = 100;
    /** Taille minimale de la frise : complétée par les événements plus anciens si le compte est calme. */
    public static final int MIN_SIZE = 10;
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
        Instant since = now.minus(AdminUserOverviewService.RECENT_DELIVERY_WINDOW);
        LocalDateTime sinceUtc = LocalDateTime.ofInstant(since, ZoneOffset.UTC);
        List<TimelineEntry> collected = new ArrayList<>();
        if (has(authorities, AdminPermission.BID_VIEW)) collected.addAll(parcelEvents(userId, sinceUtc));
        if (has(authorities, AdminPermission.PAYMENT_VIEW)) collected.addAll(paymentEvents(userId, sinceUtc));
        if (has(authorities, AdminPermission.DISPUTE_VIEW)) collected.addAll(disputeEvents(userId, sinceUtc));
        if (has(authorities, AdminPermission.REPORT_VIEW)) collected.addAll(reportEvents(userId, sinceUtc));

        // La fenêtre et le « dernier lot » se recoupent : dédoublonnage par (type, objet, titre).
        Map<String, TimelineEntry> unique = new LinkedHashMap<>();
        for (TimelineEntry e : collected) unique.putIfAbsent(e.refType() + "|" + e.refId() + "|" + e.title(), e);
        List<TimelineEntry> all = new ArrayList<>(unique.values());
        all.sort(NEWEST_FIRST);

        int recent = (int) all.stream().filter(e -> !e.at().isBefore(since)).count();
        int size = recent >= MIN_SIZE ? Math.min(recent, RECENT_CAP) : Math.min(MIN_SIZE, all.size());
        return List.copyOf(all.subList(0, size));
    }

    /** Colis : tout ce qui a bougé dans la fenêtre (borné) plus les derniers colis actifs (pour compléter). */
    private List<TimelineEntry> parcelEvents(UUID userId, LocalDateTime since) {
        Map<UUID, BidEntity> bids = new LinkedHashMap<>();
        bidRepository.findAdminActivitySinceByParty(userId, since, BidStatus.NEGOTIATION_STATUSES, PageRequest.of(0, RECENT_CAP))
                .forEach(b -> bids.putIfAbsent(b.getId(), b));
        bidRepository.findAdminRecentActivityByParty(userId, BidStatus.NEGOTIATION_STATUSES, PageRequest.of(0, MIN_SIZE))
                .forEach(b -> bids.putIfAbsent(b.getId(), b));
        if (bids.isEmpty()) return List.of();
        Map<UUID, AnnouncementEntity> announcements = announcementRepository
                .findAllById(bids.values().stream().map(BidEntity::getAnnouncementId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AnnouncementEntity::getId, Function.identity()));
        Map<UUID, Instant> handovers = trackingEventRepository
                .findByBidIdInAndEventType(List.copyOf(bids.keySet()), TrackingEventType.DEPART)
                .stream().filter(e -> e.getScannedAt() != null)
                .collect(Collectors.toMap(TrackingEventEntity::getBidId,
                        e -> e.getScannedAt().toInstant(ZoneOffset.UTC), (a, b) -> a.isBefore(b) ? a : b));
        List<TimelineEntry> out = new ArrayList<>();
        for (BidEntity b : bids.values()) {
            AnnouncementEntity a = announcements.get(b.getAnnouncementId());
            String corridor = a == null ? "" : MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity());
            String detail = parcelDetail(corridor, b.getTrackingNumber());
            add(out, "PARCEL", "Colis créé", detail, AdminUserListingService.toInstant(b.getCreatedAt()), "PARCEL", b.getId());
            add(out, "PARCEL", "Colis pris en charge", detail, handovers.get(b.getId()), "PARCEL", b.getId());
            add(out, "PARCEL", "Colis livré", detail, AdminUserListingService.toInstant(b.getDeliveredAt()), "PARCEL", b.getId());
        }
        return out;
    }

    private List<TimelineEntry> paymentEvents(UUID userId, LocalDateTime since) {
        List<AdminPaymentListItemResponse> payments = new ArrayList<>(
                paymentInsights.recentByBeneficiary(userId, since, RECENT_CAP));
        payments.addAll(paymentInsights.recentByBeneficiary(userId, null, MIN_SIZE));
        List<TimelineEntry> out = new ArrayList<>();
        for (AdminPaymentListItemResponse p : payments) {
            String detail = formatAmount(p.amountCents(), p.currency());
            // Daté de la création du paiement : capturedAt n'est pas exposé par AdminPaymentListItemResponse.
            // Comparaison tolérante : un statut inconnu ne doit jamais faire échouer la synthèse.
            if ("ESCROW".equals(p.status()) || "RELEASED".equals(p.status())) {
                add(out, "PAYMENT", "Paiement reçu", detail, AdminUserListingService.toInstant(p.createdAt()), "PAYMENT", p.id());
            }
            add(out, "PAYMENT", "Versement retenu", detail, AdminUserListingService.toInstant(p.payoutHeldAt()), "PAYMENT", p.id());
        }
        return out;
    }

    private List<TimelineEntry> disputeEvents(UUID userId, LocalDateTime since) {
        List<DisputeEntity> disputes = new ArrayList<>(
                disputeRepository.findAdminOpenedSince(userId, since, PageRequest.of(0, RECENT_CAP)));
        disputes.addAll(disputeRepository.findAdminResolvedSince(userId, since.atOffset(ZoneOffset.UTC),
                PageRequest.of(0, RECENT_CAP)));
        disputes.addAll(disputeRepository.findAdminByParty(userId, PageRequest.of(0, MIN_SIZE)).getContent());
        List<TimelineEntry> out = new ArrayList<>();
        for (DisputeEntity d : disputes) {
            String detail = disputeLabel(d.getType());
            add(out, "DISPUTE", "Litige ouvert", detail, AdminUserListingService.toInstant(d.getCreatedAt()), "DISPUTE", d.getId());
            // resolvedAt est un OffsetDateTime (colonne timestamptz) : toInstant() est déjà l'instant exact.
            add(out, "DISPUTE", "Litige résolu", detail,
                    d.getResolvedAt() == null ? null : d.getResolvedAt().toInstant(), "DISPUTE", d.getId());
        }
        return out;
    }

    private List<TimelineEntry> reportEvents(UUID userId, LocalDateTime since) {
        List<ReportEntity> reports = new ArrayList<>(
                reportRepository.findAdminReceivedSince(userId, since, PageRequest.of(0, RECENT_CAP)));
        reports.addAll(reportRepository.findAdminReceivedByUser(userId, PageRequest.of(0, MIN_SIZE)).getContent());
        List<TimelineEntry> out = new ArrayList<>();
        reports.forEach(r ->
                add(out, "REPORT", "Signalement reçu", r.getReason() == null ? null : r.getReason().label(),
                        AdminUserListingService.toInstant(r.getCreatedAt()), "REPORT", r.getId()));
        return out;
    }

    /** Libellé français d'un type de litige ; jamais le code brut. */
    private static String disputeLabel(String type) {
        if (DisputeTypes.SENDER_NO_SHOW_CONTESTED.equals(type)) return "Absence de l'expéditeur contestée";
        if (type != null && type.startsWith(DisputeTypes.ADMIN_PREFIX)) return "Litige ouvert par l'équipe";
        return "Litige sur un colis";
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
