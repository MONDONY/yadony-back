package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.dto.MatchingRequestDto;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MatchingService {

    private final AnnouncementRepository announcementRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final UserRepository userRepository;
    private final com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    private final BlockVisibility blockVisibility;
    private final com.yadony.api.config.PlatformSettingsService platformSettingsService;

    public MatchingService(
            AnnouncementRepository announcementRepository,
            PackageRequestRepository packageRequestRepository,
            UserRepository userRepository,
            com.yadony.api.payments.currency.ExchangeRateService exchangeRateService,
            BlockVisibility blockVisibility,
            com.yadony.api.config.PlatformSettingsService platformSettingsService) {
        this.announcementRepository = announcementRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.userRepository = userRepository;
        this.exchangeRateService = exchangeRateService;
        this.blockVisibility = blockVisibility;
        this.platformSettingsService = platformSettingsService;
    }

    public List<MatchingRequestDto> findMatchingRequests(UUID travelerId) {
        List<AnnouncementEntity> activeAnnouncements = openForDeposit(
                announcementRepository.findActiveByTravelerId(travelerId));

        // Résolu une fois pour tout l'appel : la liste des blocages ne dépend que
        // du voyageur, pas des demandes parcourues.
        Set<UUID> hiddenSenderIds = blockVisibility.hiddenUserIdsFor(travelerId);

        List<MatchingRequestDto> results = new ArrayList<>();

        for (AnnouncementEntity announcement : activeAnnouncements) {
            List<PackageRequestEntity> candidates = packageRequestRepository
                    .findOpenByCorridor(announcement.getDepartureCity(), announcement.getArrivalCity());

            for (PackageRequestEntity request : candidates) {
                if (!matches(request, announcement)) continue;
                if (isHiddenSender(hiddenSenderIds, request)) continue;

                Optional<UserEntity> senderOpt = userRepository.findById(request.getSenderId());
                if (senderOpt.isEmpty()) continue;

                UserEntity sender = senderOpt.get();
                results.add(toDto(request, announcement, sender));
            }
        }

        results.sort((a, b) -> Integer.compare(b.matchScore(), a.matchScore()));
        return results;
    }

    /**
     * Trajets du voyageur qui acceptent encore des colis : une demande ne peut plus être
     * proposée sur un trajet dont la date limite de dépôt est passée (409 à la création,
     * YADONY-FLUTTER-PROD-1R). Ces trajets ne servent donc plus de base aux suggestions.
     */
    static List<AnnouncementEntity> openForDeposit(List<AnnouncementEntity> announcements) {
        java.time.Instant now = java.time.Instant.now();
        return announcements.stream()
                .filter(a -> !a.isHandoverDeadlinePassed(now))
                .toList();
    }

    /**
     * L'expéditeur de cette demande est-il masqué pour le voyageur ?
     *
     * <p>Le test de nullité est nécessaire : {@code hiddenUserIdsFor} peut renvoyer
     * un {@code Set.of()} immuable, dont {@code contains(null)} lève une NPE.
     */
    private static boolean isHiddenSender(Set<UUID> hiddenSenderIds, PackageRequestEntity request) {
        UUID senderId = request.getSenderId();
        return senderId != null && hiddenSenderIds.contains(senderId);
    }

    /**
     * Meilleur match par demande pour un voyageur : identifiant du trajet retenu,
     * sa date de départ, et le score de compatibilité.
     */
    public record MatchInfo(UUID requestId, UUID tripId, LocalDate tripDepartureDate, int matchScore) {}

    /**
     * Variante dédupliquée de {@link #findMatchingRequests} destinée à la recherche
     * paginée de demandes (paramètre {@code matchingMyTrips}).
     *
     * <p>{@link #findMatchingRequests} produit un DTO par couple (trajet, demande) :
     * une demande compatible avec deux trajets du voyageur y figure deux fois. Ici
     * on ne conserve qu'une entrée par demande, celle du meilleur score, et la map
     * est ordonnée par score décroissant, cet ordre porte le tri de la page.
     *
     * <p>Contrairement à {@link #findMatchingRequests}, les expéditeurs sont chargés
     * en une seule requête ({@code findAllById}) : cette méthode est appelée à chaque
     * page de recherche et un {@code findById} par candidat saturerait le pool de
     * connexions dès qu'un voyageur cumule plusieurs corridors. Seule l'existence de
     * l'expéditeur est testée, l'entité n'est pas utilisée ici.
     *
     * <p>Les candidats retenus incluent les demandes {@code NEGOTIATING} en plus des
     * {@code OPEN}, pour rester aligné sur la recherche standard
     * ({@code PackageRequestSpecifications.openOnly}) : sans cela une demande sur
     * laquelle le voyageur ouvre un fil de négociation disparaîtrait de sa propre
     * liste filtrée.
     */
    public Map<UUID, MatchInfo> findBestMatchByRequestId(UUID travelerId) {
        List<AnnouncementEntity> activeAnnouncements = openForDeposit(
                announcementRepository.findActiveByTravelerId(travelerId));

        Set<UUID> hiddenSenderIds = blockVisibility.hiddenUserIdsFor(travelerId);

        // 1er passage : couples (trajet, demande) compatibles, sans toucher aux expéditeurs.
        List<Candidate> candidates = new ArrayList<>();
        for (AnnouncementEntity announcement : activeAnnouncements) {
            List<PackageRequestEntity> requests = packageRequestRepository
                    .findOpenOrNegotiatingByCorridor(
                            announcement.getDepartureCity(), announcement.getArrivalCity());

            for (PackageRequestEntity request : requests) {
                if (!matches(request, announcement)) continue;
                if (isHiddenSender(hiddenSenderIds, request)) continue;
                candidates.add(new Candidate(request, announcement));
            }
        }
        if (candidates.isEmpty()) {
            return Map.of();
        }

        // 2e passage : un seul chargement des expéditeurs, filtre sur leur existence.
        Set<UUID> senderIds = candidates.stream()
                .map(c -> c.request().getSenderId())
                .collect(Collectors.toSet());
        Set<UUID> existingSenderIds = userRepository.findAllById(senderIds).stream()
                .map(UserEntity::getId)
                .collect(Collectors.toSet());

        Map<UUID, MatchInfo> best = new HashMap<>();
        for (Candidate candidate : candidates) {
            PackageRequestEntity request = candidate.request();
            AnnouncementEntity announcement = candidate.announcement();
            if (!existingSenderIds.contains(request.getSenderId())) continue;

            int score = computeMatchScore(request, announcement, computeBudgetPerKg(request));
            MatchInfo current = best.get(request.getId());
            if (current == null || score > current.matchScore()) {
                best.put(request.getId(), new MatchInfo(
                        request.getId(),
                        announcement.getId(),
                        announcement.getDepartureDate(),
                        score));
            }
        }

        // Départage par requestId à score égal : l'itération d'une HashMap n'est pas
        // un ordre, la map retournée doit rester identique d'un appel à l'autre.
        return best.values().stream()
                .sorted(Comparator.comparingInt(MatchInfo::matchScore).reversed()
                        .thenComparing(MatchInfo::requestId))
                .collect(Collectors.toMap(
                        MatchInfo::requestId,
                        m -> m,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    /** Couple (demande, trajet) déjà validé par {@link #matches}, avant résolution de l'expéditeur. */
    private record Candidate(PackageRequestEntity request, AnnouncementEntity announcement) {}

    /**
     * Inverse de {@link #findMatchingRequests} : pour une demande de colis donnée,
     * retourne les ids des voyageurs dont au moins un trajet ACTIVE/FULL matche
     * (même règle corridor + poids + fenêtre de date). Utilisé par la notification
     * temps réel à la création d'une demande. Une demande non OPEN ne matche rien.
     */
    public List<UUID> findTravelersMatchingPackage(UUID requestId) {
        PackageRequestEntity request = packageRequestRepository.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != PackageRequestStatus.OPEN) {
            return List.of();
        }
        return announcementRepository
                .findActiveByCorridor(request.getDepartureCity(), request.getArrivalCity())
                .stream()
                .filter(a -> matches(request, a))
                .map(AnnouncementEntity::getTravelerId)
                .distinct()
                .toList();
    }

    /** Score de date d'une demande sans date (« Date flexible ») : exacte = 25, proche = 15. */
    static final int FLEXIBLE_DATE_SCORE = 20;

    /**
     * Règle de match commune à {@link #findMatchingRequests},
     * {@link #findBestMatchByRequestId} et {@link #findTravelersMatchingPackage} : corridor
     * déjà filtré en SQL, restent le volume (trajet au m³), le mode de transport, le poids, la taille et la
     * fenêtre de date. Point d'extension unique : ajouter un critère ici le propage aux trois chemins.
     */
    private boolean matches(PackageRequestEntity request, AnnouncementEntity announcement) {
        // Trajet vendu au m³ : la demande doit connaître son volume et il doit tenir dans le volume restant
        // (même prédicat que le rematch, ParcelFitRules.fitsTripVolume) ; sans effet sur un trajet au kilo.
        return ParcelFitRules.fitsTripVolume(announcement, request.getVolumeM3())
                && fitsMode(request, announcement)
                && fitsWeight(request, announcement)
                && fitsSize(request, announcement)
                && fitsDate(request, announcement);
    }

    /**
     * Mode choisi par l'expéditeur ; une demande « peu importe » (mode nul) accepte tout trajet.
     * Tant que les deux interrupteurs route et bateau sont éteints, le choix du mode n'existe pas
     * encore côté produit : le mode est ignoré (le client n'envoie rien, les demandes historiques
     * portent la valeur par défaut avion) et la correspondance reste celle d'avant.
     */
    private boolean fitsMode(PackageRequestEntity request, AnnouncementEntity announcement) {
        if (!platformSettingsService.transportRoadEnabled() && !platformSettingsService.transportBoatEnabled()) {
            return true;
        }
        return TransportMode.accepts(request.getTransportMode(), announcement.getTransportMode());
    }

    /**
     * Poids : une capacité en kilo libre ({@code KG_FREE}) n'est pas bornée (son {@code availableKg}
     * d'environ 1 kg est technique, même règle que {@code reserveCapacity}), seul le poids max par colis
     * compte ; sinon le poids tient dans les kilos restants, puis dans le poids max par colis quand le
     * trajet en renseigne un. Un poids max absent ne bloque rien.
     */
    private boolean fitsWeight(PackageRequestEntity request, AnnouncementEntity announcement) {
        // Trajet bateau au m³ : aucun kilo à comparer (availableKg nul), seul le poids max par colis
        // compte ici ; le volume se compare ailleurs.
        if (!isKgFree(announcement) && announcement.getAvailableKg() != null
                && request.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            return false;
        }
        return ParcelFitRules.fits(announcement, request.getWeightKg(), null);
    }

    /** Taille de la demande contre la taille max du trajet ; une taille inconnue d'un côté ne bloque rien. */
    private boolean fitsSize(PackageRequestEntity request, AnnouncementEntity announcement) {
        return ParcelFitRules.fits(announcement, null, request.getItemSize());
    }

    private static boolean isKgFree(AnnouncementEntity announcement) {
        return announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
    }

    private boolean fitsDate(PackageRequestEntity request, AnnouncementEntity announcement) {
        // « Date flexible » (V328) : une demande sans date accepte toutes les dates.
        if (request.getDesiredDate() == null) {
            return true;
        }
        long daysDiff = Math.abs(ChronoUnit.DAYS.between(
                request.getDesiredDate(), announcement.getDepartureDate()));
        return daysDiff <= request.getDateToleranceDays();
    }

    private MatchingRequestDto toDto(PackageRequestEntity request,
                                     AnnouncementEntity announcement,
                                     UserEntity sender) {
        String corridor = MatchingTextUtil.corridorLabel(announcement.getDepartureCity(), announcement.getArrivalCity());
        String senderName = MatchingTextUtil.buildPublicName(sender);
        String senderInitials = MatchingTextUtil.buildInitials(sender);
        double senderRating = sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;

        double budgetPerKg = computeBudgetPerKg(request);
        int matchScore = computeMatchScore(request, announcement, budgetPerKg);
        String messageExcerpt = MatchingTextUtil.truncate(request.getDescription(), 100);

        return new MatchingRequestDto(
                request.getId().toString(),
                announcement.getId().toString(),
                corridor,
                announcement.getDepartureDate().toString(),
                // Null pour un trajet bateau au m³ (pas de kilos) : clé absente du JSON.
                announcement.getAvailableKg() == null ? null : announcement.getAvailableKg().doubleValue(),
                sender.getId().toString(),
                senderName,
                senderInitials,
                senderRating,
                sender.getTotalShipments(),
                request.getWeightKg().doubleValue(),
                request.getContentCategory(),
                budgetPerKg,
                request.getPhotoUrl(),
                messageExcerpt,
                matchScore,
                request.getCreatedAt().toString(),
                request.getCurrency(),
                announcement.getTransportMode() != null ? announcement.getTransportMode().name() : null,
                request.getTransportMode() != null ? request.getTransportMode().name() : null
        );
    }

    private int computeMatchScore(PackageRequestEntity request,
                                  AnnouncementEntity announcement,
                                  double budgetPerKg) {
        // Kilo libre : pas de capacité réelle à remplir, la part « poids » est neutre (la moitié).
        int weightScore;
        // Trajet bateau au m³ (sans kilos) : même part neutre que le kilo libre.
        if (isKgFree(announcement) || announcement.getAvailableKg() == null) {
            weightScore = 20;
        } else {
            double ratio = request.getWeightKg().divide(announcement.getAvailableKg(),
                    4, java.math.RoundingMode.HALF_UP).doubleValue();
            weightScore = (int) Math.round((1.0 - Math.min(ratio, 1.0)) * 40);
        }

        // Budget (devise de la demande) et prix (devise de l'annonce) ne se comparent
        // que sur une échelle commune : le pivot EUR. Sans conversion, 5000 XOF/kg de
        // budget « couvrait » n'importe quel prix EUR (5000 >= 8), et un budget 10 €/kg
        // échouait face à 6000 F CFA/kg qui n'en valent que ~9. Même devise → brut,
        // exact et sans bruit de taux.
        // Trajet bateau au m³ : un budget au kilo ne se compare pas à un prix au m³, part neutre.
        int budgetScore;
        if (announcement.getPricePerKg() == null) {
            budgetScore = 20;
        } else {
            double pricePerKg = announcement.getPricePerKg().doubleValue();
            double budgetComparable = budgetPerKg;
            double priceComparable = pricePerKg;
            String requestCurrency = request.getCurrency() != null ? request.getCurrency() : "EUR";
            String announcementCurrency = announcement.getCurrency() != null ? announcement.getCurrency() : "EUR";
            if (!requestCurrency.equalsIgnoreCase(announcementCurrency)) {
                budgetComparable = exchangeRateService.toEurPivot(
                        java.math.BigDecimal.valueOf(budgetPerKg), requestCurrency).doubleValue();
                java.math.BigDecimal pivot = announcement.getPricePerKgEur() != null
                        ? announcement.getPricePerKgEur()
                        : exchangeRateService.toEurPivot(
                                announcement.getPricePerKg(), announcementCurrency);
                priceComparable = pivot.doubleValue();
            }
            if (budgetComparable >= priceComparable) {
                budgetScore = 35;
            } else if (budgetComparable >= priceComparable * 0.8) {
                budgetScore = 20;
            } else {
                budgetScore = 5;
            }
        }

        int dateScore;
        long daysDiff = request.getDesiredDate() == null ? 0L : Math.abs(ChronoUnit.DAYS.between(
                request.getDesiredDate(), announcement.getDepartureDate()));
        if (request.getDesiredDate() == null) {
            // Compatible avec toutes les dates, mais sans date précise qui colle au trajet :
            // un cran sous la correspondance exacte, au-dessus du « presque ».
            dateScore = FLEXIBLE_DATE_SCORE;
        } else if (daysDiff <= request.getDateToleranceDays()) {
            dateScore = 25;
        } else if (daysDiff <= request.getDateToleranceDays() + 3L) {
            dateScore = 15;
        } else {
            dateScore = 5;
        }

        return Math.min(100, weightScore + budgetScore + dateScore);
    }

    private double computeBudgetPerKg(PackageRequestEntity request) {
        if (request.getTargetPriceEur() == null
                || request.getWeightKg().compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return request.getTargetPriceEur()
                .divide(request.getWeightKg(), 4, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

}
