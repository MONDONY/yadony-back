package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminPackageRequestDetailResponse;
import com.yadony.api.admin.dto.AdminPackageRequestListItemResponse;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.service.PackageRequestModerationService;
import com.yadony.api.requests.service.PackageRequestPhotoService;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportTargetType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lecture admin des demandes d'envoi (liste filtrée et fiche). Écritures : voir
 * {@link PackageRequestModerationService}.
 *
 * <p>Les demandes soft-deletées (annulées par leur expéditeur) restent hors champ, comme
 * partout ({@code @SQLRestriction} de l'entité).
 */
@Service
public class AdminPackageRequestQueryService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Duration PHOTO_TTL = Duration.ofMinutes(15);

    private final PackageRequestRepository requestRepository;
    private final NegotiationThreadRepository threadRepository;
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContactService;
    private final PackageRequestPhotoService photoService;
    private final StorageService storageService;
    private final PackageRequestModerationService moderationService;

    public AdminPackageRequestQueryService(PackageRequestRepository requestRepository,
                                           NegotiationThreadRepository threadRepository,
                                           ReportRepository reportRepository,
                                           UserRepository userRepository,
                                           FirebaseContactService firebaseContactService,
                                           PackageRequestPhotoService photoService,
                                           StorageService storageService,
                                           PackageRequestModerationService moderationService) {
        this.requestRepository = requestRepository;
        this.threadRepository = threadRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.firebaseContactService = firebaseContactService;
        this.photoService = photoService;
        this.storageService = storageService;
        this.moderationService = moderationService;
    }

    /**
     * @param query  identifiant exact (demande ou expéditeur), sous-chaîne d'une ville, du nom
     *               ou du pseudo de l'expéditeur, ou numéro E.164 ({@code +…}) résolu par
     *               Firebase comme dans la recherche admin des utilisateurs (le téléphone
     *               n'est pas en base)
     * @param from   borne basse de {@code createdAt} : date ISO (début du jour UTC) ou
     *               date-heure ISO
     * @param to     borne haute incluse : date ISO (jour entier) ou date-heure ISO
     */
    @Transactional(readOnly = true)
    public Page<AdminPackageRequestListItemResponse> list(PackageRequestStatus status, String query,
                                                          boolean reportedOnly, String from, String to,
                                                          int page, int size) {
        LocalDateTime fromBound = parseBound(from, false);
        LocalDateTime toBound = parseBound(to, true);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<PackageRequestEntity> requests = requestRepository.findAll(
                filter(status, normalize(query), reportedOnly, fromBound, toBound), pageable);

        List<UUID> ids = requests.getContent().stream().map(PackageRequestEntity::getId).toList();
        Map<UUID, Long> reportCounts = reportCounts(ids);
        Map<UUID, Long> openCounts = ids.isEmpty() ? Map.of()
                : threadRepository.findByPackageRequestIdIn(ids).stream()
                        .filter(t -> t.getStatus().isActive())
                        .collect(Collectors.groupingBy(NegotiationThreadEntity::getPackageRequestId,
                                Collectors.counting()));
        Map<UUID, String> names = namesOf(requests.getContent().stream()
                .map(PackageRequestEntity::getSenderId).collect(Collectors.toSet()));

        return requests.map(r -> new AdminPackageRequestListItemResponse(
                r.getId(), r.getSenderId(), names.get(r.getSenderId()),
                r.getDepartureCity(), r.getArrivalCity(), r.getDesiredDate(), r.getWeightKg(),
                r.getParcelSize(), r.getTransportMode(), r.getStatus(), r.getCurrency(),
                r.getTargetPriceEur(), r.getCreatedAt(),
                reportCounts.getOrDefault(r.getId(), 0L), openCounts.getOrDefault(r.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public AdminPackageRequestDetailResponse detail(UUID id) {
        PackageRequestEntity r = requestRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        PackageRequestModerationService.NOT_FOUND, "Package Request Not Found",
                        "Demande d'envoi introuvable"));

        List<NegotiationThreadEntity> threads = new ArrayList<>(threadRepository.findByPackageRequestId(id));
        threads.sort(Comparator.comparing(NegotiationThreadEntity::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<ReportEntity> reports = reportRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType.PACKAGE_REQUEST, id);

        Set<UUID> userIds = new HashSet<>();
        userIds.add(r.getSenderId());
        threads.forEach(t -> userIds.add(t.getTravelerId()));
        reports.forEach(rep -> userIds.add(rep.getReporterId()));
        Map<UUID, String> names = namesOf(userIds);

        List<AdminPackageRequestDetailResponse.Photo> photos = photoService.activePhotos(id).stream()
                .map(p -> new AdminPackageRequestDetailResponse.Photo(p.url()))
                .toList();
        if (photos.isEmpty() && r.getPhotoUrl() != null && !r.getPhotoUrl().isBlank()) {
            // Champ legacy : une clé S3 interne (jamais une URL, cf. sanitizeLegacyPhotoUrl).
            photos = List.of(new AdminPackageRequestDetailResponse.Photo(
                    storageService.generatePresignedUrl(r.getPhotoUrl(), PHOTO_TTL)));
        }

        String blocked = moderationService.removalBlockedReason(r, threads);
        boolean removedByAdmin = r.getStatus() == PackageRequestStatus.REMOVED_BY_ADMIN;
        long openNegotiations = threads.stream().filter(t -> t.getStatus().isActive()).count();

        return new AdminPackageRequestDetailResponse(
                r.getId(), r.getSenderId(), names.get(r.getSenderId()),
                r.getDepartureCity(), r.getArrivalCity(), r.getDesiredDate(),
                r.getDateToleranceDays() != null ? r.getDateToleranceDays() : 0,
                r.getWeightKg(), r.getParcelSize(), r.getTransportMode(), r.getStatus(), r.getCurrency(),
                r.getTargetPriceEur(), r.getCreatedAt(), reports.size(), openNegotiations,
                r.getDescription(), r.getContentCategory(),
                r.getPickupNeighborhood(), r.getDeliveryNeighborhood(),
                r.getPickupAddressLabel(), r.getDeliveryAddressLabel(), r.getRecipientCity(),
                r.getAcceptedPaymentMethods(), r.isNegotiable(), r.getStatusBeforeRemoval(),
                photos,
                threads.stream().map(t -> new AdminPackageRequestDetailResponse.Negotiation(
                        t.getId(), t.getTravelerId(), names.get(t.getTravelerId()), t.getStatus(),
                        t.getCurrentPriceEur(), t.getCurrency(), t.getUpdatedAt())).toList(),
                reports.stream().map(rep -> new AdminPackageRequestDetailResponse.Report(
                        rep.getId(), rep.getReporterId(), names.get(rep.getReporterId()),
                        rep.getReason() != null ? rep.getReason().name() : null, rep.getDescription(),
                        rep.getStatus() != null ? rep.getStatus().name() : null,
                        rep.getCreatedAt())).toList(),
                blocked == null, blocked, removedByAdmin);
    }

    // ── Filtres ─────────────────────────────────────────────────────────────

    private Specification<PackageRequestEntity> filter(PackageRequestStatus status, String query,
                                                       boolean reportedOnly,
                                                       LocalDateTime from, LocalDateTime to) {
        String phoneUid = query != null && query.startsWith("+")
                ? firebaseContactService.findUidByPhone(query).orElse(null)
                : null;
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }
            if (reportedOnly) {
                Subquery<UUID> reported = cq.subquery(UUID.class);
                Root<ReportEntity> rep = reported.from(ReportEntity.class);
                reported.select(rep.get("targetId")).where(
                        cb.equal(rep.get("targetType"), ReportTargetType.PACKAGE_REQUEST),
                        cb.isNull(rep.get("deletedAt")));
                predicates.add(root.get("id").in(reported));
            }
            if (query != null) {
                UUID asId = parseUuid(query);
                if (asId != null) {
                    predicates.add(cb.or(cb.equal(root.get("id"), asId), cb.equal(root.get("senderId"), asId)));
                } else {
                    String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
                    Subquery<UUID> senders = cq.subquery(UUID.class);
                    Root<UserEntity> u = senders.from(UserEntity.class);
                    Predicate byName = cb.or(
                            cb.like(cb.lower(cb.concat(cb.concat(cb.coalesce(u.get("firstName"), ""), " "),
                                    cb.coalesce(u.get("lastName"), ""))), like),
                            cb.like(cb.lower(u.get("username")), like));
                    senders.select(u.get("id")).where(phoneUid != null
                            ? cb.or(byName, cb.equal(u.get("firebaseUid"), phoneUid))
                            : byName);
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("departureCity")), like),
                            cb.like(cb.lower(root.get("arrivalCity")), like),
                            root.get("senderId").in(senders)));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static String normalize(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Date ISO ({@code 2026-09-01}) ou date-heure ISO, avec ou sans décalage (ramené en UTC,
     * la colonne étant en UTC). Borne haute d'une date seule : le jour entier est inclus.
     */
    static LocalDateTime parseBound(String value, boolean upper) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            if (v.length() == 10) {
                LocalDate day = LocalDate.parse(v);
                return upper ? day.plusDays(1).atStartOfDay() : day.atStartOfDay();
            }
            LocalDateTime at;
            try {
                at = OffsetDateTime.parse(v).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } catch (DateTimeParseException noOffset) {
                at = LocalDateTime.parse(v);
            }
            return upper ? at.plusNanos(1) : at;
        } catch (DateTimeParseException e) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-date", "Invalid Date",
                    "Date invalide : " + v + " (attendu : AAAA-MM-JJ ou date-heure ISO)");
        }
    }

    private Map<UUID, Long> reportCounts(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return reportRepository.countByTargetIds(ReportTargetType.PACKAGE_REQUEST, ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
    }

    private Map<UUID, String> namesOf(Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        // Boucle et non Collectors.toMap : buildName peut rendre null, que toMap refuse.
        Map<UUID, String> names = new java.util.HashMap<>();
        for (UserEntity u : userRepository.findAllById(ids)) {
            if (u.getId() != null) {
                names.putIfAbsent(u.getId(), MatchingTextUtil.buildName(u));
            }
        }
        return names;
    }
}
