package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserProStatusChangedEvent;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import com.yadony.api.matching.dto.AnnouncementPriceGridItemResponse;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.dto.TravelerProfileDto;
import com.yadony.api.matching.events.AnnouncementDeletedEvent;
import com.yadony.api.matching.events.AnnouncementInProgressEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.TripArrivedEvent;
import com.yadony.api.matching.AnnouncementPublishedEvent;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Paris");

    /**
     * Dérive l'instant canonique de départ : (date + heure) interprétées dans le
     * fuseau de la ville de départ (défaut Europe/Paris). Sert de backstop temporel
     * au verrou d'annulation après remise (D1/D3).
     *
     * <p>Tolérant : retourne {@code null} si la date ou l'heure manque, sans pré-empter
     * les autres validations (date limite de dépôt, date passée…). L'obligation de l'heure
     * de départ est portée par {@code @NotNull} sur {@code AnnouncementRequest.departureTime}
     * (validation bean au niveau du contrôleur).
     */
    static OffsetDateTime deriveDepartureAt(LocalDate date, LocalTime time, String zone) {
        if (date == null || time == null) {
            return null;
        }
        ZoneId resolved = (zone == null || zone.isBlank()) ? DEFAULT_ZONE : ZoneId.of(zone);
        return date.atTime(time).atZone(resolved).toOffsetDateTime();
    }

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final YadonyConfigProperties config;
    private final PriceGridService priceGridService;
    private final com.yadony.api.country.FlagService flagService;
    private final StorageService storageService;
    private final FavoriteRepository favoriteRepository;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final AnnouncementSearchMapper announcementSearchMapper;
    private final PackageRequestRepository packageRequestRepository;
    private final NegotiationThreadRepository negotiationThreadRepository;

    @Value("${yadony.kyc.enforce:true}")
    private boolean enforceKyc;

    @Value("${yadony.stripe.enforce:true}")
    private boolean enforceStripeOnboarding;

    public AnnouncementService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            UserRepository userRepository,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher,
            YadonyConfigProperties config,
            PriceGridService priceGridService,
            com.yadony.api.country.FlagService flagService,
            StorageService storageService,
            FavoriteRepository favoriteRepository,
            ActiveCurrencyResolver activeCurrencyResolver,
            AnnouncementSearchMapper announcementSearchMapper,
            PackageRequestRepository packageRequestRepository,
            NegotiationThreadRepository negotiationThreadRepository
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.priceGridService = priceGridService;
        this.flagService = flagService;
        this.storageService = storageService;
        this.favoriteRepository = favoriteRepository;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.announcementSearchMapper = announcementSearchMapper;
        this.packageRequestRepository = packageRequestRepository;
        this.negotiationThreadRepository = negotiationThreadRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "announcements-search", key = "#departureCity + '_' + #arrivalCity + '_' + #departureDateFrom + '_' + #departureDateTo + '_' + #minAvailableKg + '_' + #maxAvailableKg + '_' + #maxPricePerKg + '_' + #minRating + '_' + #kiloProOnly + '_' + #weekendOnly + '_' + #transportMode + '_' + #kycVerifiedOnly + '_' + #contentType + '_' + #userLat + '_' + #userLng + '_' + #radiusKm + '_' + #sortBy + '_' + #sortDir + '_' + #pageable.pageNumber + '_' + #viewerFirebaseUid + '_' + #urgent")
    public Page<AnnouncementSearchResponse> searchAnnouncements(
            String departureCity, String arrivalCity,
            LocalDate departureDateFrom, LocalDate departureDateTo,
            BigDecimal minAvailableKg, BigDecimal maxAvailableKg,
            BigDecimal maxPricePerKg, BigDecimal minRating,
            Boolean kiloProOnly, Boolean weekendOnly,
            String transportMode, Boolean kycVerifiedOnly, String contentType,
            Double userLat, Double userLng, Double radiusKm,
            String sortBy, String sortDir, Pageable pageable,
            String viewerFirebaseUid, Boolean urgent) {

        // Confidentialité v2 — exclure (dans les deux sens) les voyageurs en relation
        // de blocage avec le viewer. Le firebaseUid est intégré à la clé de cache pour
        // éviter qu'un utilisateur reçoive les résultats filtrés d'un autre.
        UUID viewerId = (viewerFirebaseUid == null || viewerFirebaseUid.isBlank())
                ? null
                : userRepository.findByFirebaseUid(viewerFirebaseUid)
                        .map(UserEntity::getId)
                        .orElse(null);
        String viewerCurrency = activeCurrencyResolver.resolve(viewerId);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.hasStatus(AnnouncementStatus.ACTIVE)
                .and(AnnouncementSpecification.publicOrOpenSurplus())
                .and(AnnouncementSpecification.hasCurrency(viewerCurrency));

        if (viewerId != null)
            spec = spec.and(AnnouncementSpecification.notBlockedBy(viewerId));

        if (departureCity != null && !departureCity.isBlank())
            spec = spec.and(AnnouncementSpecification.hasDepartureCity(departureCity));
        if (arrivalCity != null && !arrivalCity.isBlank())
            spec = spec.and(AnnouncementSpecification.hasArrivalCity(arrivalCity));
        // urgent=true restreint la fenêtre de départ à [today, today+seuil] (UTC) ;
        // combiné à un filtre de date explicite, on applique l'intersection des deux bornes.
        LocalDate effectiveFrom = departureDateFrom;
        LocalDate effectiveTo = departureDateTo;
        if (Boolean.TRUE.equals(urgent)) {
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            LocalDate urgentTo = today.plusDays(config.urgency().thresholdDays());
            effectiveFrom = (effectiveFrom == null || effectiveFrom.isBefore(today)) ? today : effectiveFrom;
            effectiveTo = (effectiveTo == null || effectiveTo.isAfter(urgentTo)) ? urgentTo : effectiveTo;
        }

        if (effectiveFrom != null)
            spec = spec.and(AnnouncementSpecification.departureDateFrom(effectiveFrom));
        if (effectiveTo != null)
            spec = spec.and(AnnouncementSpecification.departureDateTo(effectiveTo));
        if (minAvailableKg != null)
            spec = spec.and(AnnouncementSpecification.minAvailableKg(minAvailableKg));
        if (maxAvailableKg != null)
            spec = spec.and(AnnouncementSpecification.maxAvailableKg(maxAvailableKg));
        if (maxPricePerKg != null)
            spec = spec.and(AnnouncementSpecification.maxPricePerKg(maxPricePerKg));
        if (Boolean.TRUE.equals(weekendOnly))
            spec = spec.and(AnnouncementSpecification.weekendOnly());
        if (minRating != null)
            spec = spec.and(AnnouncementSpecification.minRating(minRating));
        if (Boolean.TRUE.equals(kiloProOnly))
            spec = spec.and(AnnouncementSpecification.kiloProOnly());
        if (transportMode != null && !transportMode.isBlank()) {
            try {
                TransportMode mode = TransportMode.valueOf(transportMode.toUpperCase());
                spec = spec.and(AnnouncementSpecification.hasTransportMode(mode));
            } catch (IllegalArgumentException ignored) {
                // invalid enum value → ignore filter, don't crash
            }
        }
        if (Boolean.TRUE.equals(kycVerifiedOnly))
            spec = spec.and(AnnouncementSpecification.kycVerifiedOnly());
        if (contentType != null && !contentType.isBlank())
            spec = spec.and(AnnouncementSpecification.hasAcceptedContentType(contentType));

        // Radius filter: only active when ALL 3 params provided
        if (userLat != null && userLng != null && radiusKm != null && radiusKm > 0) {
            List<UUID> idsInRadius = announcementRepository.findIdsWithinPickupRadius(
                            userLat, userLng, radiusKm)
                    .stream()
                    .map(UUID::fromString)
                    .toList();
            spec = spec
                    .and(AnnouncementSpecification.hasPickupCoordinates())
                    .and(AnnouncementSpecification.idIn(idsInRadius));
        }

        Sort sort = buildSort(sortBy, sortDir);
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        // Batch-load favorite trip IDs for the caller (single query, no N+1).
        // Anonymous callers (viewerId == null) get isFavorite=false for all results.
        final Set<UUID> favIds;
        if (viewerId != null) {
            favIds = new HashSet<>(favoriteRepository.findTargetIds(viewerId, FavoriteTargetType.TRIP));
        } else {
            favIds = Set.of();
        }

        Page<AnnouncementEntity> page = announcementRepository.findAll(spec, sortedPageable);

        // Batch-load all related data for the page to eliminate N+1 queries.
        List<UUID> announcementIds = page.getContent().stream().map(AnnouncementEntity::getId).toList();
        List<UUID> travelerIds = page.getContent().stream().map(AnnouncementEntity::getTravelerId).distinct().toList();

        Map<UUID, UserEntity> userMap = userRepository.findAllById(travelerIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u, (a2, b2) -> a2));

        Map<UUID, Long> bidCountMap = announcementIds.isEmpty() ? Map.of() :
                bidRepository.countVisibleByAnnouncementIds(announcementIds).stream()
                        .collect(Collectors.toMap(
                                row -> (UUID) row[0],
                                row -> (Long) row[1],
                                (a2, b2) -> a2));

        // Batch grid items only for MIXED-priced announcements (avoids calling priceGridService per row).
        // priceGridService does not expose a batch API yet; left per-row only for MIXED announcements.
        // Non-MIXED rows get an empty list directly in the mapper.
        Map<UUID, List<AnnouncementPriceGridItemResponse>> gridItemMap = new HashMap<>();
        for (AnnouncementEntity a : page.getContent()) {
            if (a.getPricingMode() == PricingMode.MIXED) {
                gridItemMap.put(a.getId(), priceGridService.getAnnouncementGridItems(a.getId(), a.getTravelerId()));
            }
        }

        return page.map(a -> announcementSearchMapper.toSearchResponse(
                a, favIds.contains(a.getId()), userMap, bidCountMap, gridItemMap));
    }

    private Sort buildSort(String sortBy, String sortDir) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort proFirst = Sort.by(Sort.Direction.DESC, "travelerIsPro");
        Sort secondary = switch (sortBy != null ? sortBy : "date") {
            case "price" -> Sort.by(direction, "pricePerKg");
            default -> Sort.by(direction, "departureDate");
        };
        return proFirst.and(secondary);
    }

    /**
     * Prix « affiché expéditeur » (net + commission Yadony) pour le mode KG, symétrique de
     * {@code unitPriceDisplay} du mode MIXED. Source unique du multiplicateur :
     * {@link PriceGridService#displayPrice}. {@code null} si aucun prix au kilo (MIXED pur).
     */
    private java.math.BigDecimal pricePerKgDisplay(java.math.BigDecimal net, java.util.UUID travelerId) {
        return net == null ? null : priceGridService.displayPrice(net, travelerId);
    }

    /**
     * Reusable mapper: converts an {@link AnnouncementEntity} to an {@link AnnouncementSearchResponse}.
     * The {@code isFavorite} flag is supplied by the caller so this method remains pure and testable.
     * Delegates to {@link AnnouncementSearchMapper} so that external packages can also call
     * the mapper directly without injecting this service.
     */
    public AnnouncementSearchResponse toSearchResponse(AnnouncementEntity entity, boolean isFavorite) {
        return announcementSearchMapper.toSearchResponse(entity, isFavorite);
    }

    /**
     * Délègue à {@link UserEntity#publicDisplayName()}, qui ne rend jamais {@code null}.
     *
     * <p>Cette méthode retournait auparavant {@code null} pour un compte sans nom, et le client
     * comblait ce vide avec le numéro de téléphone du voyageur : une coordonnée personnelle
     * s'affichait comme nom sur les cartes de recherche. Le nom de famille est par ailleurs
     * réduit à son initiale, comme partout ailleurs, alors qu'il était ici rendu en entier.
     */
    private String buildDisplayName(UserEntity user) {
        return user.publicDisplayName();
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public AnnouncementResponse createAnnouncement(String firebaseUid, AnnouncementRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));

        // D4 : voyageur suspendu de publication (retour de colis non rendu, décision admin).
        // S'applique aussi bien à un brouillon qu'à une publication directe.
        assertPublishingNotSuspended(user);

        boolean isDraft = request.isDraft();

        if (isDraft) {
            YadonyConfigProperties.Limits limits = config.limits() != null
                    ? config.limits()
                    : new YadonyConfigProperties.Limits(null, null);
            int maxDrafts = user.isProAccount() ? limits.maxDraftsPro() : limits.maxDrafts();
            long draftCount = announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT)
                    + packageRequestRepository.countBySenderIdAndStatus(user.getId(), PackageRequestStatus.DRAFT);
            if (draftCount >= maxDrafts) {
                throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "draft-limit-reached",
                        "Draft Limit Reached",
                        "Limite de " + maxDrafts + " brouillon(s) atteinte."
                                + (user.isProAccount() ? "" : " Passez en PRO pour en créer davantage."));
            }
        }

        if (!isDraft) {
            // Publication directe : mêmes contrôles que publishAnnouncement (DRAFT→ACTIVE).
            assertCanPublish(user);
        }

        if (!user.getRoles().contains(Role.TRAVELER)) {
            user.getRoles().add(Role.TRAVELER);
            userRepository.save(user);
        }

        Set<PaymentMethod> paymentMethods = resolvePaymentMethods(request.acceptedPaymentMethods(), user);

        if (!isDraft) {
            // La capacité « carte » exige Stripe Connect ; le cash-only est libre.
            assertStripeCapability(user, paymentMethods);
        }

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(user.getId());
        String creatorCurrency = activeCurrencyResolver.resolve(user.getId());
        announcement.setCurrency(creatorCurrency);
        announcement.setTravelerIsPro(user.isProAccount());
        announcement.setDepartureCity(request.departureCity());
        announcement.setArrivalCity(request.arrivalCity());
        announcement.setDepartureCountryCode(request.departureCountryCode());
        announcement.setArrivalCountryCode(request.arrivalCountryCode());
        announcement.setDepartureDate(request.departureDate());
        announcement.setDepartureTime(request.departureTime());
        announcement.setArrivalTime(request.arrivalTime());
        announcement.setDepartureAt(deriveDepartureAt(
                request.departureDate(), request.departureTime(), announcement.getTimezone()));
        announcement.setPickupAddressLabel(request.pickupAddress().label());
        announcement.setPickupLat(java.math.BigDecimal.valueOf(request.pickupAddress().lat()));
        announcement.setPickupLng(java.math.BigDecimal.valueOf(request.pickupAddress().lng()));
        announcement.setDeliveryAddressLabel(request.deliveryAddress().label());
        announcement.setDeliveryLat(java.math.BigDecimal.valueOf(request.deliveryAddress().lat()));
        announcement.setDeliveryLng(java.math.BigDecimal.valueOf(request.deliveryAddress().lng()));
        announcement.setAvailableKg(request.availableKg());
        announcement.setTotalKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());
        announcement.setTransportMode(request.transportMode());
        announcement.setStatus(isDraft ? AnnouncementStatus.DRAFT : AnnouncementStatus.ACTIVE);
        announcement.setDescription(request.description());
        // Normalisé à l'écriture (C2) — cf. ContentCategoryNormalizer javadoc.
        if (request.acceptedContentTypes() != null)
            announcement.setAcceptedContentTypes(ContentCategoryNormalizer.normalizeList(request.acceptedContentTypes()));
        if (request.refusedTypes() != null)
            announcement.setRefusedTypes(ContentCategoryNormalizer.normalizeList(request.refusedTypes()));
        announcement.setAcceptedPaymentMethods(paymentMethods);
        announcement.setCapacityUnit(
            request.capacityUnit() != null ? request.capacityUnit() : CapacityUnit.SUITCASE_23KG
        );
        PricingMode pricingMode = request.pricingMode() != null ? request.pricingMode() : PricingMode.KG;
        announcement.setPricingMode(pricingMode);
        if (pricingMode == PricingMode.KG &&
                (request.pricePerKg() == null || request.pricePerKg().compareTo(java.math.BigDecimal.ZERO) <= 0)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-price",
                    "Prix invalide",
                    "Le prix par kg est obligatoire en mode KG"
            );
        }
        if (request.departureDate() != null && request.departureDate().isBefore(LocalDate.now())) {
            throw new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid-departure-date",
                "Date invalide",
                "La date de départ ne peut pas être dans le passé"
            );
        }

        validateHandoverDeadline(request.handoverDeadline(),
                request.departureDate(), request.departureTime());
        announcement.setHandoverDeadline(request.handoverDeadline());
        // Absent = prix ferme : un client pas encore à jour ne doit jamais ouvrir
        // un trajet à la négociation sans que le voyageur l'ait demandé.
        announcement.setNegotiable(request.isNegotiable());

        AnnouncementEntity saved = announcementRepository.save(announcement);

        if (pricingMode == PricingMode.MIXED) {
            priceGridService.snapshotToAnnouncement(user.getId(), saved.getId());
        }

        auditService.log(
                "USER",
                user.getId(),
                "ANNOUNCEMENT_CREATED",
                saved.getId(),
                Map.of(
                        "departureCity", saved.getDepartureCity(),
                        "arrivalCity", saved.getArrivalCity(),
                        "departureDate", saved.getDepartureDate().toString(),
                        "availableKg", saved.getAvailableKg().toString(),
                        "pricePerKg", saved.getPricePerKg().toString(),
                        "transportMode", saved.getTransportMode().name(),
                        "status", saved.getStatus().name()
                )
        );

        // Un brouillon est invisible des expéditeurs : pas de matching/notification/alerte
        // corridor tant qu'il n'a pas été explicitement publié (voir publishAnnouncement).
        if (!isDraft) {
            publishMatchingEvents(saved, user);
        }

        return toResponse(saved);
    }

    /**
     * Events déclenchant matching (alertes corridor), stats de corridor et notifications
     * d'abonnement voyageur — publiés uniquement quand une annonce devient réellement
     * visible (création directe non-brouillon, ou publication d'un brouillon).
     */
    private void publishMatchingEvents(AnnouncementEntity saved, UserEntity user) {
        eventPublisher.publishEvent(new com.yadony.api.matching.events.AnnouncementCreatedEvent(
            saved.getId(),
            saved.getDepartureCity(),
            "",
            saved.getArrivalCity(),
            ""
        ));

        eventPublisher.publishEvent(new AnnouncementPublishedEvent(
            saved.getId(),
            saved.getTravelerId(),
            // publicDisplayName() plutôt qu'une concaténation : des champs nuls produisaient
            // « null null » dans le corps de la notification envoyée aux expéditeurs.
            user.publicDisplayName(),
            saved.getDepartureCity(),
            saved.getArrivalCity()
        ));
    }

    @Transactional
    public Page<AnnouncementResponse> getMyAnnouncements(
            String firebaseUid, AnnouncementStatus statusFilter, String q,
            LocalDate date, LocalDate dateFrom, LocalDate dateTo,
            String departure, String arrival, Pageable pageable) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        // Transition inline: before returning the list, check if any ACTIVE/FULL
        // announcements have passed their departure time and update them immediately.
        // This makes the "En cours" status appear as soon as the traveler opens the screen,
        // without waiting for the hourly scheduler.
        triggerInProgressTransitions();

        String qParam         = (q         != null && !q.isBlank())         ? q.trim()         : null;
        String departureParam = (departure != null && !departure.isBlank()) ? departure.trim() : null;
        String arrivalParam   = (arrival   != null && !arrival.isBlank())   ? arrival.trim()   : null;
        Page<AnnouncementEntity> page = announcementRepository.findByTravelerIdFiltered(
                user.getId(), statusFilter, qParam, date, dateFrom, dateTo, departureParam, arrivalParam, pageable);
        return page.map(this::toResponse);
    }

    public List<com.yadony.api.matching.dto.CorridorDto> getMyCorridors(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        return announcementRepository
                .findTopDestinationsForTraveler(user.getId(), PageRequest.of(0, 100))
                .stream()
                .map(d -> new com.yadony.api.matching.dto.CorridorDto(d.from(), d.to()))
                .toList();
    }

    /**
     * Checks all ACTIVE/FULL announcements whose departure time has passed and transitions
     * them to IN_PROGRESS (or directly COMPLETED if no ACCEPTED bids remain).
     * Called inline on each "Mes trajets" load, and also by the hourly scheduler as a safety net.
     *
     * @Transactional requis : BidExpiredOnDepartureEvent est écouté en
     * AFTER_COMMIT — publié hors transaction (chemin scheduler), l'event serait
     * silencieusement perdu et les remboursements jamais déclenchés.
     */
    @Transactional
    public void triggerInProgressTransitions() {
        Instant now = Instant.now();
        // Fetch broad (timezone-agnostic, +1 jour de marge pour couvrir tout
        // décalage horaire), puis décider trajet par trajet dans SON fuseau —
        // et non avec un « maintenant » Europe/Paris global pour tous.
        LocalDate maxDate = now.atZone(DEFAULT_ZONE).toLocalDate().plusDays(1);
        List<AnnouncementEntity> candidates =
                announcementRepository.findActiveOrFullDepartingOnOrBefore(maxDate);

        for (AnnouncementEntity announcement : candidates) {
            if (!hasDeparted(announcement, now)) {
                continue;
            }
            try {
                applyInProgressTransition(announcement);
            } catch (Exception e) {
                log.error("Inline transition failed for announcement {}: {}",
                        announcement.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Un trajet est « parti » quand son instant de départ — (date + heure)
     * interprétées dans le fuseau PROPRE du trajet — est atteint ou dépassé par
     * {@code now}. Sans heure de départ, il est parti une fois sa date locale
     * entièrement passée. Statique + paramètre {@code now} → testable de façon
     * déterministe, indépendamment de l'horloge et du fuseau du serveur.
     */
    static boolean hasDeparted(AnnouncementEntity a, Instant now) {
        LocalDate depDate = a.getDepartureDate();
        if (depDate == null) {
            return false;
        }
        ZoneId zone = resolveZoneOrDefault(a.getTimezone());
        LocalTime depTime = a.getDepartureTime();
        if (depTime != null) {
            Instant departureAt = depDate.atTime(depTime).atZone(zone).toInstant();
            return !departureAt.isAfter(now);
        }
        return depDate.isBefore(now.atZone(zone).toLocalDate());
    }

    private static ZoneId resolveZoneOrDefault(String zone) {
        if (zone == null || zone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(zone);
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    private void applyInProgressTransition(AnnouncementEntity announcement) {
        AnnouncementStatus previous = announcement.getStatus();
        boolean hasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatusIn(
                announcement.getId(), List.copyOf(BidStatus.IN_FLIGHT));

        if (!hasAcceptedBids) {
            announcement.setStatus(AnnouncementStatus.COMPLETED);
            announcementRepository.save(announcement);
            auditService.log("ANNOUNCEMENT", announcement.getTravelerId(),
                    "ANNOUNCEMENT_COMPLETED", announcement.getId(),
                    Map.of("previousStatus", previous.name(), "trigger", "DEPARTURE_NO_ACCEPTED_BIDS"));
            log.info("Announcement {} → COMPLETED (no ACCEPTED bids at departure)", announcement.getId());
        } else {
            announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
            announcementRepository.save(announcement);
            auditService.log("ANNOUNCEMENT", announcement.getTravelerId(),
                    "ANNOUNCEMENT_IN_PROGRESS", announcement.getId(),
                    Map.of("previousStatus", previous.name()));
            eventPublisher.publishEvent(
                    new AnnouncementInProgressEvent(announcement.getId(), announcement.getTravelerId()));
            log.info("Announcement {} → IN_PROGRESS", announcement.getId());
        }

        expirePendingBids(announcement);
    }

    private void expirePendingBids(AnnouncementEntity announcement) {
        List<BidEntity> pendingBids = bidRepository.findByAnnouncementIdAndStatusIn(
                announcement.getId(),
                List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING));
        for (BidEntity bid : pendingBids) {
            bid.setStatus(BidStatus.EXPIRED);
            bidRepository.save(bid);
            auditService.log("BID", bid.getId(), "BID_EXPIRED_ON_DEPARTURE",
                    announcement.getTravelerId(),
                    Map.of("announcementId", announcement.getId().toString()));
            eventPublisher.publishEvent(new BidExpiredOnDepartureEvent(
                    bid.getId(), bid.getSenderId(), announcement.getId(), announcement.getTravelerId()));
        }
    }

    @Transactional(readOnly = true)
    public AnnouncementDetailResponse getAnnouncementDetail(UUID id, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        // Un brouillon est invisible des tiers : même erreur que si l'annonce n'existait
        // pas, pour ne pas révéler son existence à un utilisateur non-propriétaire.
        if (announcement.getStatus() == AnnouncementStatus.DRAFT) {
            UUID viewerId = userRepository.findByFirebaseUid(firebaseUid)
                    .map(UserEntity::getId)
                    .orElse(null);
            if (viewerId == null || !viewerId.equals(announcement.getTravelerId())) {
                throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Announcement Not Found", "Annonce introuvable");
            }
        }

        long bidsCount = bidRepository.countVisibleByAnnouncementId(id);
        long confirmedParcelCount = bidRepository.countByAnnouncementIdAndStatusIn(
                id,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.COMPLETED)
        );

        // Les instructions de retrait décrivent une adresse / un point de rendez-vous
        // physique : elles ne sont visibles que des parties du trajet (le voyageur
        // propriétaire, et les expéditeurs ayant un colis actif dessus). L'endpoint
        // reste ouvert à tout utilisateur authentifié, seul ce champ est masqué.
        String arrivalInstructions = canSeeArrivalInstructions(announcement, firebaseUid)
                ? announcement.getArrivalInstructions()
                : null;

        UserEntity traveler = userRepository.findById(announcement.getTravelerId()).orElse(null);
        boolean kycVerified = traveler != null && traveler.getKycStatus() == KycStatus.VERIFIED;
        TravelerProfileDto travelerDto = traveler != null
                ? new TravelerProfileDto(
                        traveler.getId(),
                        buildDisplayName(traveler),
                        traveler.getAverageRating() != null ? traveler.getAverageRating().doubleValue() : null,
                        null,
                        traveler.isKiloPro(),
                        traveler.isProAccount(),
                        kycVerified,
                        storageService.avatarUrl(traveler.getAvatarUrl()),
                        !traveler.isContactKycOnly())
                : null;

        List<com.yadony.api.matching.dto.AnnouncementPriceGridItemResponse> gridItems =
                announcement.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(announcement.getId(), announcement.getTravelerId())
                        : List.of();

        return new AnnouncementDetailResponse(
                announcement.getId(),
                announcement.getTravelerId(),
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                announcement.getDepartureDate(),
                announcement.getDepartureTime(),
                announcement.getArrivalTime(),
                new com.yadony.api.matching.dto.AddressDto(announcement.getPickupAddressLabel(), announcement.getPickupLat().doubleValue(), announcement.getPickupLng().doubleValue()),
                new com.yadony.api.matching.dto.AddressDto(announcement.getDeliveryAddressLabel(), announcement.getDeliveryLat().doubleValue(), announcement.getDeliveryLng().doubleValue()),
                announcement.getAvailableKg(),
                announcement.getTotalKg(),
                announcement.getPricePerKg(),
                pricePerKgDisplay(announcement.getPricePerKg(), announcement.getTravelerId()),
                announcement.getTransportMode(),
                announcement.getStatus().name(),
                bidsCount,
                confirmedParcelCount,
                travelerDto,
                announcement.getDescription(),
                announcement.getAcceptedContentTypes(),
                announcement.getRefusedTypes(),
                announcement.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                announcement.getCapacityUnit(),
                announcement.getAcceptedPaymentMethods().contains(com.yadony.api.payments.cash.PaymentMethod.CASH),
                announcement.getCreatedAt(),
                announcement.getUpdatedAt(),
                announcement.getPricingMode(),
                gridItems,
                announcement.getReservedKg(),
                announcement.isSurplusEligible(),
                announcement.isSurplusPublished(),
                announcement.getHandoverDeadline(),
                announcement.getCurrency(),
                arrivalInstructions
        );
    }

    /**
     * Qui a le droit de lire {@code arrivalInstructions} : le voyageur propriétaire du
     * trajet, et tout expéditeur ayant un colis encore actif dessus (statut hors
     * {@link #INACTIVE_BID_STATUSES}). Un expéditeur dont le colis a été refusé/annulé,
     * comme n'importe quel autre utilisateur authentifié, n'a plus de raison légitime de
     * connaître le point de retrait.
     */
    private boolean canSeeArrivalInstructions(AnnouncementEntity announcement, String firebaseUid) {
        if (announcement.getArrivalInstructions() == null || firebaseUid == null) {
            return false;
        }
        UUID viewerId = userRepository.findByFirebaseUid(firebaseUid).map(UserEntity::getId).orElse(null);
        if (viewerId == null) {
            return false;
        }
        if (viewerId.equals(announcement.getTravelerId())) {
            return true;
        }
        return bidRepository.existsByAnnouncementIdAndSenderIdAndStatusNotIn(
                announcement.getId(), viewerId, INACTIVE_BID_STATUSES);
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public AnnouncementDetailResponse updateAnnouncement(UUID id, String firebaseUid, AnnouncementRequest request) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'êtes pas autorisé à modifier cette annonce");
        }

        // ARRIVED inclus : un colis arrivé mais pas encore retiré est toujours un
        // engagement en cours, modifier le trajet sous ses pieds n'a pas de sens.
        boolean hasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatusIn(
                id, List.copyOf(BidStatus.IN_FLIGHT));
        if (hasAcceptedBids) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT,
                    "modification-impossible",
                    "Modification Impossible",
                    "Modification impossible : des colis sont déjà acceptés pour ce trajet"
            );
        }

        final com.yadony.api.matching.TransportMode oldTransportMode = announcement.getTransportMode();

        PricingMode updatePricingMode = request.pricingMode() != null ? request.pricingMode() : announcement.getPricingMode();
        if (updatePricingMode == PricingMode.KG &&
                (request.pricePerKg() == null || request.pricePerKg().compareTo(java.math.BigDecimal.ZERO) <= 0)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-price",
                    "Prix invalide",
                    "Le prix par kg est obligatoire en mode KG"
            );
        }

        validateHandoverDeadline(request.handoverDeadline(),
                request.departureDate(), request.departureTime());

        announcement.setDepartureCity(request.departureCity());
        announcement.setArrivalCity(request.arrivalCity());
        announcement.setDepartureCountryCode(request.departureCountryCode());
        announcement.setArrivalCountryCode(request.arrivalCountryCode());
        announcement.setDepartureDate(request.departureDate());
        announcement.setDepartureTime(request.departureTime());
        announcement.setArrivalTime(request.arrivalTime());
        announcement.setDepartureAt(deriveDepartureAt(
                request.departureDate(), request.departureTime(), announcement.getTimezone()));
        announcement.setHandoverDeadline(request.handoverDeadline());
        announcement.setNegotiable(request.isNegotiable());
        announcement.setPickupAddressLabel(request.pickupAddress().label());
        announcement.setPickupLat(java.math.BigDecimal.valueOf(request.pickupAddress().lat()));
        announcement.setPickupLng(java.math.BigDecimal.valueOf(request.pickupAddress().lng()));
        announcement.setDeliveryAddressLabel(request.deliveryAddress().label());
        announcement.setDeliveryLat(java.math.BigDecimal.valueOf(request.deliveryAddress().lat()));
        announcement.setDeliveryLng(java.math.BigDecimal.valueOf(request.deliveryAddress().lng()));
        announcement.setAvailableKg(request.availableKg());
        // Update is blocked if any bid is ACCEPTED, so no booked weight to preserve → keep total in sync.
        announcement.setTotalKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());
        announcement.setTransportMode(request.transportMode());
        announcement.setDescription(request.description());
        // Normalisé à l'écriture (C2) — cf. ContentCategoryNormalizer javadoc.
        if (request.acceptedContentTypes() != null)
            announcement.setAcceptedContentTypes(ContentCategoryNormalizer.normalizeList(request.acceptedContentTypes()));
        if (request.refusedTypes() != null)
            announcement.setRefusedTypes(ContentCategoryNormalizer.normalizeList(request.refusedTypes()));
        if (request.acceptedPaymentMethods() != null) {
            Set<PaymentMethod> updatedMethods = resolvePaymentMethods(request.acceptedPaymentMethods(), user);
            if (announcement.getStatus() != AnnouncementStatus.DRAFT) {
                assertStripeCapability(user, updatedMethods);
            }
            announcement.setAcceptedPaymentMethods(updatedMethods);
        }
        if (request.capacityUnit() != null) {
            announcement.setCapacityUnit(request.capacityUnit());
        }

        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log(
                "USER",
                user.getId(),
                "ANNOUNCEMENT_UPDATED",
                saved.getId(),
                Map.of(
                        "departureCity", saved.getDepartureCity(),
                        "arrivalCity", saved.getArrivalCity(),
                        "departureDate", saved.getDepartureDate().toString(),
                        "availableKg", saved.getAvailableKg().toString(),
                        "pricePerKg", saved.getPricePerKg().toString(),
                        "transportMode_old", oldTransportMode.name(),
                        "transportMode_new", saved.getTransportMode().name()
                )
        );

        long bidsCount = bidRepository.countVisibleByAnnouncementId(id);
        long confirmedParcelCount = bidRepository.countByAnnouncementIdAndStatusIn(
                id,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.COMPLETED)
        );

        boolean kycVerified = user.getKycStatus() == KycStatus.VERIFIED;
        TravelerProfileDto updatedTravelerDto = new TravelerProfileDto(
                user.getId(),
                buildDisplayName(user),
                null, null, false, user.isProAccount(), kycVerified,
                user.getAvatarUrl(),
                !user.isContactKycOnly());

        List<com.yadony.api.matching.dto.AnnouncementPriceGridItemResponse> updatedGridItems =
                saved.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(saved.getId(), saved.getTravelerId())
                        : List.of();

        return new AnnouncementDetailResponse(
                saved.getId(),
                saved.getTravelerId(),
                saved.getDepartureCity(),
                saved.getArrivalCity(),
                saved.getDepartureDate(),
                saved.getDepartureTime(),
                saved.getArrivalTime(),
                new com.yadony.api.matching.dto.AddressDto(saved.getPickupAddressLabel(), saved.getPickupLat().doubleValue(), saved.getPickupLng().doubleValue()),
                new com.yadony.api.matching.dto.AddressDto(saved.getDeliveryAddressLabel(), saved.getDeliveryLat().doubleValue(), saved.getDeliveryLng().doubleValue()),
                saved.getAvailableKg(),
                saved.getTotalKg(),
                saved.getPricePerKg(),
                pricePerKgDisplay(saved.getPricePerKg(), saved.getTravelerId()),
                saved.getTransportMode(),
                saved.getStatus().name(),
                bidsCount,
                confirmedParcelCount,
                updatedTravelerDto,
                saved.getDescription(),
                saved.getAcceptedContentTypes(),
                saved.getRefusedTypes(),
                saved.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                saved.getCapacityUnit(),
                saved.getAcceptedPaymentMethods().contains(com.yadony.api.payments.cash.PaymentMethod.CASH),
                saved.getCreatedAt(),
                saved.getUpdatedAt(),
                saved.getPricingMode(),
                updatedGridItems,
                saved.getReservedKg(),
                saved.isSurplusEligible(),
                saved.isSurplusPublished(),
                saved.getHandoverDeadline(),
                saved.getCurrency(),
                saved.getArrivalInstructions()
        );
    }

    /**
     * Publie un brouillon (DRAFT → ACTIVE) : exécute tous les contrôles de publication
     * ({@link #assertCanPublish}) puis la validation de date de départ, avant de rendre
     * l'annonce visible des expéditeurs (events de matching/notification déclenchés ici).
     */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public AnnouncementDetailResponse publishAnnouncement(UUID id, String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            // Même pattern d'ownership que updateAnnouncement (statut + code identiques).
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à publier cette annonce");
        }

        if (announcement.getStatus() != AnnouncementStatus.DRAFT) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "not-a-draft",
                    "Not A Draft", "Seul un brouillon peut être publié");
        }

        assertCanPublish(user);
        assertStripeCapability(user, announcement.getAcceptedPaymentMethods());

        if (announcement.getDepartureDate() != null
                && announcement.getDepartureDate().isBefore(LocalDate.now())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "departure-date-passed",
                    "Departure Date Passed",
                    "La date de départ est passée. Modifiez le trajet avant de le publier.");
        }

        announcement.setStatus(AnnouncementStatus.ACTIVE);
        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log("USER", user.getId(), "ANNOUNCEMENT_PUBLISHED", saved.getId(),
                Map.of("departureCity", saved.getDepartureCity(),
                       "arrivalCity", saved.getArrivalCity(),
                       "departureDate", saved.getDepartureDate().toString()));

        // Le brouillon devient réel : c'est ici (et non à sa création) que les expéditeurs
        // doivent en être informés — cf. matching/notifications/alertes corridor.
        publishMatchingEvents(saved, user);

        return getAnnouncementDetail(saved.getId(), firebaseUid);
    }

    /**
     * Retire un trajet de la circulation sans l'annuler (ACTIVE → DRAFT).
     *
     * <p>Un trajet ne peut être dépublié qu'avant sa première demande : un expéditeur
     * ayant déjà sollicité le voyageur a agi sur la foi de sa publication.
     */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public AnnouncementDetailResponse unpublishAnnouncement(UUID id, String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));

        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à dépublier cette annonce");
        }
        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "announcement/not-unpublishable",
                    "Not Unpublishable", "Seul un trajet actif peut être dépublié");
        }
        if (bidRepository.countVisibleByAnnouncementId(id) > 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "announcement/has-bids",
                    "Has Bids", "Ce trajet a déjà reçu des demandes et ne peut plus être dépublié");
        }
        if (negotiationThreadRepository.existsActiveByTravelerAnnouncementId(id)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "announcement/has-negotiations",
                    "Has Negotiations", "Ce trajet est lié à une négociation et ne peut plus être dépublié");
        }

        YadonyConfigProperties.Limits limits = config.limits() != null
                ? config.limits()
                : new YadonyConfigProperties.Limits(null, null);
        int maxDrafts = user.isProAccount() ? limits.maxDraftsPro() : limits.maxDrafts();
        long draftCount = announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT)
                + packageRequestRepository.countBySenderIdAndStatus(user.getId(), PackageRequestStatus.DRAFT);
        if (draftCount >= maxDrafts) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "draft-limit-reached",
                    "Draft Limit Reached",
                    "Limite de " + maxDrafts + " brouillon(s) atteinte."
                            + (user.isProAccount() ? "" : " Passez en PRO pour en créer davantage."));
        }

        announcement.setStatus(AnnouncementStatus.DRAFT);
        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log("ANNOUNCEMENT", saved.getId(), "UNPUBLISHED", user.getId(),
                Map.of("departureCity", saved.getDepartureCity(),
                        "arrivalCity", saved.getArrivalCity()));

        return getAnnouncementDetail(saved.getId(), firebaseUid);
    }

    /**
     * Statuts de bid exclus du calcul « colis actifs pris en charge » : jamais
     * pris en charge (REJECTED/CANCELLED/EXPIRED), abandonné (NO_SHOW/PARCEL_REFUSED),
     * déjà au bout du parcours (COMPLETED), ou pas encore réservé (NEGOTIATING).
     *
     * <p>NEGOTIATING en fait partie parce que les TROIS usages de cette constante
     * demandent « ce colis est-il pris en charge par le voyageur ? », et un fil de
     * négociation ouvert n'est pas un colis pris en charge :
     * <ul>
     *   <li>{@code canSeeArrivalInstructions} — un expéditeur qui discute encore le
     *       prix n'a aucune raison légitime de connaître le point de retrait ;</li>
     *   <li>{@code markArrived} — un bid en négociation ne doit jamais basculer en
     *       ARRIVED, ni bloquer le marquage d'arrivée par la garde « tous IN_TRANSIT » ;</li>
     *   <li>{@code updateArrivalInstructions} — un fil ouvert ne doit pas faire croire
     *       que le trajet n'est pas soldé.</li>
     * </ul>
     * La garde « cet expéditeur a-t-il déjà une demande sur ce trajet ? » ne passe
     * PAS par cette constante : elle vit dans {@code BidService#createBid} et
     * {@code BidCheckoutService}, via {@code existsBySenderIdAndAnnouncementIdAndStatusIn},
     * qui liste NEGOTIATING explicitement. Les deux sémantiques restent donc séparées.
     */
    private static final Set<BidStatus> INACTIVE_BID_STATUSES = EnumSet.of(
            BidStatus.REJECTED, BidStatus.CANCELLED, BidStatus.PARCEL_REFUSED,
            BidStatus.EXPIRED, BidStatus.NO_SHOW, BidStatus.COMPLETED,
            BidStatus.NEGOTIATING);

    private record OwnedAnnouncement(UserEntity user, AnnouncementEntity announcement) {}

    /**
     * Charge le trajet verrouillé pour mise à jour et vérifie que l'appelant en
     * est le voyageur propriétaire. Commun à {@link #markArrived} et
     * {@link #updateArrivalInstructions}.
     */
    private OwnedAnnouncement loadOwnedAnnouncementForUpdate(UUID id, String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));

        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à modifier ce trajet");
        }

        return new OwnedAnnouncement(user, announcement);
    }

    /**
     * Marque tous les colis activement pris en charge sur ce trajet comme
     * arrivés à destination (IN_TRANSIT → ARRIVED), en une action groupée par
     * le voyageur. Refuse si un colis actif n'est pas encore IN_TRANSIT (reste
     * à embarquer) ou si aucun colis n'est actuellement pris en charge.
     */
    @Transactional
    public AnnouncementDetailResponse markArrived(UUID id, String firebaseUid, String arrivalInstructions) {
        OwnedAnnouncement owned = loadOwnedAnnouncementForUpdate(id, firebaseUid);
        UserEntity user = owned.user();
        AnnouncementEntity announcement = owned.announcement();

        List<BidEntity> activeBids = bidRepository.findByAnnouncementIdAndStatusNotIn(id, INACTIVE_BID_STATUSES);

        if (activeBids.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "trip/no-active-parcel",
                    "No Active Parcel", "Aucun colis n'est actuellement pris en charge sur ce trajet");
        }

        boolean allInTransit = activeBids.stream().allMatch(b -> b.getStatus() == BidStatus.IN_TRANSIT);
        if (!allInTransit) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "trip/not-all-in-transit",
                    "Not All In Transit",
                    "Tous les colis doivent être en transit avant de marquer l'arrivée");
        }

        announcement.setArrivalInstructions(arrivalInstructions);
        for (BidEntity bid : activeBids) {
            bid.setStatus(BidStatus.ARRIVED);
        }
        bidRepository.saveAll(activeBids);
        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log("ANNOUNCEMENT", saved.getId(), "TRIP_ARRIVED", user.getId(),
                Map.of("bidCount", activeBids.size()));

        List<TripArrivedEvent.BidTarget> targets = activeBids.stream()
                .map(b -> new TripArrivedEvent.BidTarget(b.getId(), b.getSenderId()))
                .toList();
        eventPublisher.publishEvent(new TripArrivedEvent(saved.getId(), targets));

        return getAnnouncementDetail(saved.getId(), firebaseUid);
    }

    /**
     * Édite le texte d'instructions de retrait après le marquage initial.
     * Refuse une fois le trajet totalement soldé (plus aucun colis actif —
     * tout est livré/annulé/refusé), pour éviter de modifier une information
     * qui n'a plus personne à qui s'adresser.
     */
    @Transactional
    public AnnouncementDetailResponse updateArrivalInstructions(UUID id, String firebaseUid, String arrivalInstructions) {
        AnnouncementEntity announcement = loadOwnedAnnouncementForUpdate(id, firebaseUid).announcement();

        List<BidEntity> activeBids = bidRepository.findByAnnouncementIdAndStatusNotIn(id, INACTIVE_BID_STATUSES);
        if (activeBids.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "trip/already-delivered",
                    "Already Delivered", "Ce trajet est totalement soldé, les instructions ne peuvent plus être modifiées");
        }

        // Le trajet doit réellement être arrivé : sans cette garde, un voyageur
        // pouvait publier des instructions de retrait à ses expéditeurs alors que
        // les colis sont encore à embarquer ou en vol. Le marquage initial passe
        // par markArrived(), qui accepte les instructions au moment même du
        // basculement — cet endpoint ne sert qu'à l'édition ultérieure.
        boolean hasArrived = bidRepository.existsByAnnouncementIdAndStatusIn(
                id, List.of(BidStatus.ARRIVED, BidStatus.COMPLETED));
        if (!hasArrived) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "trip/not-arrived-yet",
                    "Not Arrived Yet",
                    "Marquez d'abord le trajet comme arrivé avant de modifier les instructions de retrait");
        }

        announcement.setArrivalInstructions(arrivalInstructions);
        AnnouncementEntity saved = announcementRepository.save(announcement);

        return getAnnouncementDetail(saved.getId(), firebaseUid);
    }

    /**
     * Contrôles de publication partagés entre {@link #createAnnouncement} (chemin non-brouillon)
     * et {@link #publishAnnouncement} (DRAFT→ACTIVE) : suspension de publication, KYC vérifié,
     * limite mensuelle d'annonces (hors PRO).
     */
    private void assertCanPublish(UserEntity user) {
        assertPublishingNotSuspended(user);

        if (enforceKyc && user.getKycStatus() != KycStatus.VERIFIED) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN,
                    "kyc-not-verified",
                    "KYC Not Verified",
                    "Vous devez compléter votre vérification d'identité pour effectuer cette action"
            );
        }

        if (!user.isProAccount() && config.limits() != null) {
            YearMonth current = YearMonth.now();
            LocalDateTime from = current.atDay(1).atStartOfDay();
            LocalDateTime to = current.atEndOfMonth().atTime(23, 59, 59);
            long count = announcementRepository.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                    user.getId(), from, to, AnnouncementStatus.DRAFT);
            if (count >= config.limits().monthlyAnnouncements()) {
                throw new YadonyBusinessException(
                        HttpStatus.FORBIDDEN,
                        "pro-limit-reached",
                        "Monthly announcement limit reached",
                        "Vous avez atteint votre limite de " + config.limits().monthlyAnnouncements()
                                + " annonces ce mois-ci. Passez en PRO pour continuer."
                );
            }
        }
    }

    /**
     * La capacité « accepter la carte » exige un onboarding Stripe Connect complet.
     * Un trajet cash-only est publiable sans compte Stripe (D3/D4 — spec
     * voyageur-universel). Gouverné par yadony.stripe.enforce (kill-switch).
     */
    private void assertStripeCapability(UserEntity user, Set<PaymentMethod> methods) {
        if (enforceStripeOnboarding
                && methods.contains(PaymentMethod.STRIPE)
                && user.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN,
                    "stripe-onboarding-incomplete",
                    "Stripe Onboarding Incomplete",
                    "Connectez votre compte bancaire pour accepter la carte, "
                    + "ou publiez votre trajet en espèces uniquement");
        }
    }

    /** D4 : voyageur suspendu de publication (retour de colis non rendu, décision admin). */
    private void assertPublishingNotSuspended(UserEntity user) {
        if (user.isPublishingSuspended()) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "publishing-suspended",
                    "Publishing Suspended",
                    "La publication de trajets est suspendue. Contactez le support.");
        }
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public void deleteAnnouncement(UUID id, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'êtes pas autorisé à supprimer cette annonce");
        }

        if (announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            // Soft-delete all associated bids (already CANCELLED from the cancellation flow)
            List<BidEntity> bids = bidRepository.findByAnnouncementId(id);
            for (BidEntity bid : bids) {
                bid.softDelete();
                bidRepository.save(bid);
            }

            announcement.softDelete();
            announcementRepository.save(announcement);

            // Notify cancellation package to clean up rematch suggestions
            eventPublisher.publishEvent(new AnnouncementDeletedEvent(id, user.getId()));

            auditService.log("ANNOUNCEMENT", user.getId(), "CANCELLED_ANNOUNCEMENT_DELETED", id,
                    Map.of("departureCity", announcement.getDepartureCity(),
                            "arrivalCity", announcement.getArrivalCity(),
                            "deletedBidsCount", String.valueOf(bids.size())));
            return;
        }

        if (announcement.getStatus() == AnnouncementStatus.DRAFT) {
            // Un brouillon n'a jamais été publié : aucun bid n'a pu être placé dessus,
            // donc pas de remboursement ni de rejet de bids à gérer — soft-delete direct.
            // Sans cette branche, le slot de brouillon (quota 1 pour un compte standard)
            // resterait verrouillé à vie si l'utilisateur ne publie/supprime jamais.
            announcement.softDelete();
            announcementRepository.save(announcement);

            auditService.log("ANNOUNCEMENT", user.getId(), "DRAFT_ANNOUNCEMENT_DELETED", id,
                    Map.of("departureCity", announcement.getDepartureCity(),
                            "arrivalCity", announcement.getArrivalCity()));
            return;
        }

        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "deletion-impossible", "Deletion Impossible",
                    "Seuls les trajets actifs ou annulés peuvent être supprimés");
        }

        // ARRIVED inclus : le colis est arrivé mais pas encore retiré, la
        // transaction n'est pas soldée — supprimer le trajet la ferait disparaître.
        if (bidRepository.existsByAnnouncementIdAndStatusIn(
                id, List.copyOf(BidStatus.IN_FLIGHT))) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "deletion-impossible", "Deletion Impossible", "Suppression impossible : des colis sont déjà acceptés pour ce trajet");
        }

        List<BidEntity> pendingBids = bidRepository.findByAnnouncementIdAndStatusIn(
                id, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED));
        for (BidEntity bid : pendingBids) {
            bid.setStatus(BidStatus.REJECTED);
            // Rejet « technique » (annonce supprimée), pas un refus du voyageur :
            // marqué pour être exclu du taux d'acceptation.
            bid.setRejectionReason(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);
            bidRepository.save(bid);
            auditService.log("BID", bid.getId(), "BID_REJECTED_ANNOUNCEMENT_DELETED", user.getId(),
                    Map.of("announcementId", id.toString(), "senderId", bid.getSenderId().toString()));
        }

        announcement.softDelete();
        announcementRepository.save(announcement);

        auditService.log("ANNOUNCEMENT", user.getId(), "ANNOUNCEMENT_DELETED", id,
                Map.of("departureCity", announcement.getDepartureCity(),
                        "arrivalCity", announcement.getArrivalCity(),
                        "rejectedBidsCount", String.valueOf(pendingBids.size())));
    }

    private AnnouncementResponse toResponse(AnnouncementEntity entity) {
        long pendingBidCount = bidRepository.countVisibleByAnnouncementId(entity.getId());
        long confirmedParcelCount = bidRepository.countByAnnouncementIdAndStatusIn(
                entity.getId(),
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.COMPLETED)
        );
        boolean cashAccepted = entity.getAcceptedPaymentMethods()
                .contains(com.yadony.api.payments.cash.PaymentMethod.CASH);
        List<com.yadony.api.matching.dto.AnnouncementPriceGridItemResponse> gridItems =
                entity.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(entity.getId(), entity.getTravelerId())
                        : List.of();
        return new AnnouncementResponse(
                entity.getId(),
                entity.getTravelerId(),
                entity.getDepartureCity(),
                entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getDepartureTime(),
                entity.getArrivalTime(),
                new com.yadony.api.matching.dto.AddressDto(entity.getPickupAddressLabel(), entity.getPickupLat().doubleValue(), entity.getPickupLng().doubleValue()),
                new com.yadony.api.matching.dto.AddressDto(entity.getDeliveryAddressLabel(), entity.getDeliveryLat().doubleValue(), entity.getDeliveryLng().doubleValue()),
                entity.getAvailableKg(),
                entity.getTotalKg(),
                entity.getPricePerKg(),
                pricePerKgDisplay(entity.getPricePerKg(), entity.getTravelerId()),
                entity.getTransportMode(),
                entity.getStatus().name(),
                pendingBidCount,
                confirmedParcelCount,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                entity.getCapacityUnit(),
                cashAccepted,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPricingMode(),
                gridItems,
                entity.getReservedKg(),
                entity.isSurplusEligible(),
                entity.isSurplusPublished(),
                entity.getDepartureCountryCode(),
                entity.getArrivalCountryCode(),
                flagService.getFlag(entity.getDepartureCountryCode()),
                flagService.getFlag(entity.getArrivalCountryCode()),
                entity.getHandoverDeadline(),
                entity.getCurrency(),
                entity.isNegotiable()
        );
    }

    @Transactional(readOnly = true)
    public java.util.List<com.yadony.api.matching.dto.TravelerAnnouncementResponse> getTravelerAnnouncements(java.util.UUID travelerId) {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 50,
            org.springframework.data.domain.Sort.by("departureDate").ascending());
        var active = announcementRepository.findByTravelerIdAndStatus(travelerId, AnnouncementStatus.ACTIVE, pageable).getContent();
        var full   = announcementRepository.findByTravelerIdAndStatus(travelerId, AnnouncementStatus.FULL, pageable).getContent();
        return java.util.stream.Stream.concat(active.stream(), full.stream())
            .map(a -> new com.yadony.api.matching.dto.TravelerAnnouncementResponse(
                a.getId(), a.getDepartureCity(), a.getArrivalCity(),
                a.getDepartureDate(), a.getPricePerKg(), a.getAvailableKg(), a.getStatus().name(),
                a.getCurrency()))
            .toList();
    }

    @EventListener
    @Transactional
    public void onUserProStatusChanged(UserProStatusChangedEvent event) {
        int updated = announcementRepository.updateTravelerProStatus(event.userId(), event.isPro());
        log.info("PRO status change for user {} (isPro={}) — {} open announcements updated",
                event.userId(), event.isPro(), updated);
    }

    private Set<PaymentMethod> resolvePaymentMethods(Set<PaymentMethod> requested, UserEntity traveler) {
        if (requested == null || requested.isEmpty()) {
            // Défaut aligné sur la capacité réelle : jamais STRIPE pour un
            // voyageur sans onboarding complet (le trajet serait invendable).
            return traveler.getStripeAccountStatus() == StripeAccountStatus.ONBOARDING_COMPLETE
                    ? EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH)
                    : EnumSet.of(PaymentMethod.CASH);
        }
        // La vérification de la capacité de paiement de la commission (wallet ou carte)
        // est reportée à l'acceptation du bid (CashCommissionService.acceptCashBid).
        // Un voyageur peut offrir le cash dès lors qu'il a un compte Yadony,
        // même sans carte de commission enregistrée (le wallet prend en charge).
        return EnumSet.copyOf(requested);
    }

    /**
     * Valide la date limite de dépôt saisie à la création/édition d'un trajet.
     * Règles : non nulle et <= départ (date+heure si présente, sinon fin du
     * jour de départ — on ne remet pas un colis après le départ du voyageur).
     *
     * Il n'y a plus de borne basse : le trajet accepte les colis dès sa
     * publication, et jusqu'à cette date limite.
     */
    private void validateHandoverDeadline(LocalDateTime deadline,
                                          LocalDate departureDate,
                                          LocalTime departureTime) {
        if (deadline == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "handover-deadline-required", "Date limite requise",
                    "La date limite de dépôt est obligatoire");
        }
        LocalDateTime departureBound = departureTime != null
                ? departureDate.atTime(departureTime)
                : departureDate.atTime(LocalTime.MAX);
        if (deadline.isAfter(departureBound)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "handover-after-departure", "Date limite après le départ",
                    "La date limite de dépôt doit précéder le départ du voyageur");
        }
    }
}
