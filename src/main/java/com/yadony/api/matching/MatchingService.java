package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
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

    public MatchingService(
            AnnouncementRepository announcementRepository,
            PackageRequestRepository packageRequestRepository,
            UserRepository userRepository) {
        this.announcementRepository = announcementRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.userRepository = userRepository;
    }

    public List<MatchingRequestDto> findMatchingRequests(UUID travelerId) {
        List<AnnouncementEntity> activeAnnouncements =
                announcementRepository.findActiveByTravelerId(travelerId);

        List<MatchingRequestDto> results = new ArrayList<>();

        for (AnnouncementEntity announcement : activeAnnouncements) {
            List<PackageRequestEntity> candidates = packageRequestRepository
                    .findOpenByCorridor(announcement.getDepartureCity(), announcement.getArrivalCity());

            for (PackageRequestEntity request : candidates) {
                if (!matches(request, announcement)) continue;

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
        List<AnnouncementEntity> activeAnnouncements =
                announcementRepository.findActiveByTravelerId(travelerId);

        // 1er passage : couples (trajet, demande) compatibles, sans toucher aux expéditeurs.
        List<Candidate> candidates = new ArrayList<>();
        for (AnnouncementEntity announcement : activeAnnouncements) {
            List<PackageRequestEntity> requests = packageRequestRepository
                    .findOpenOrNegotiatingByCorridor(
                            announcement.getDepartureCity(), announcement.getArrivalCity());

            for (PackageRequestEntity request : requests) {
                if (!matches(request, announcement)) continue;
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

    /**
     * Règle de match commune à {@link #findMatchingRequests} et
     * {@link #findBestMatchByRequestId} : corridor déjà filtré en SQL, restent le
     * poids et la fenêtre de date. Point d'extension unique — ajouter un critère
     * ici le propage aux deux chemins.
     */
    private boolean matches(PackageRequestEntity request, AnnouncementEntity announcement) {
        return fitsWeight(request, announcement) && fitsDate(request, announcement);
    }

    private boolean fitsWeight(PackageRequestEntity request, AnnouncementEntity announcement) {
        return request.getWeightKg().compareTo(announcement.getAvailableKg()) <= 0;
    }

    private boolean fitsDate(PackageRequestEntity request, AnnouncementEntity announcement) {
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
                announcement.getAvailableKg().doubleValue(),
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
                request.getCurrency()
        );
    }

    private int computeMatchScore(PackageRequestEntity request,
                                  AnnouncementEntity announcement,
                                  double budgetPerKg) {
        double ratio = request.getWeightKg().divide(announcement.getAvailableKg(),
                4, java.math.RoundingMode.HALF_UP).doubleValue();
        int weightScore = (int) Math.round((1.0 - Math.min(ratio, 1.0)) * 40);

        double pricePerKg = announcement.getPricePerKg().doubleValue();
        int budgetScore;
        if (budgetPerKg >= pricePerKg) {
            budgetScore = 35;
        } else if (budgetPerKg >= pricePerKg * 0.8) {
            budgetScore = 20;
        } else {
            budgetScore = 5;
        }

        long daysDiff = Math.abs(ChronoUnit.DAYS.between(
                request.getDesiredDate(), announcement.getDepartureDate()));
        int dateScore;
        if (daysDiff <= request.getDateToleranceDays()) {
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
