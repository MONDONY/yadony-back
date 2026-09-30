package com.yadony.api.cancellation;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementSpecification;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 5.6 — suggestions de trajets alternatifs après annulation d'un trajet.
 * Une liste de suggestions est générée PAR cancellation (donc par expéditeur affecté),
 * filtrée sur la capacité (weightKg) du bid annulé de CET expéditeur. Remplace l'ancienne
 * logique inline de {@code CancellationService.generateRematchSuggestions} qui ne générait
 * des suggestions que pour le premier expéditeur affecté et faisait un full scan
 * {@code announcementRepository.findAll()} sans vérification de capacité.
 */
@Service
public class RematchService {

    static final int MAX_SUGGESTIONS = 5;
    static final int WINDOW_DAYS = 3;

    private final AnnouncementRepository announcementRepository;
    private final RematchSuggestionRepository rematchSuggestionRepository;
    private final CancellationRepository cancellationRepository;
    private final UserRepository userRepository;

    public record RematchInfo(UUID cancellationId, int suggestionCount) {}

    public RematchService(AnnouncementRepository announcementRepository,
                           RematchSuggestionRepository rematchSuggestionRepository,
                           CancellationRepository cancellationRepository,
                           UserRepository userRepository) {
        this.announcementRepository = announcementRepository;
        this.rematchSuggestionRepository = rematchSuggestionRepository;
        this.cancellationRepository = cancellationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Génère les suggestions de rematch pour CHAQUE cancellation (une par bid affecté),
     * chacune filtrée sur la capacité du bid de son expéditeur. {@code affectedBids} et
     * {@code cancellations} doivent être de même taille et alignés index à index (même
     * ordre que dans {@code CancellationService.cancelTrip}).
     *
     * @return map senderId → RematchInfo (cancellationId + nombre de suggestions générées)
     */
    public Map<UUID, RematchInfo> generateForCancellations(AnnouncementEntity cancelled,
                                                             List<BidEntity> affectedBids,
                                                             List<CancellationEntity> cancellations) {
        Map<UUID, RematchInfo> result = new HashMap<>();
        if (affectedBids.isEmpty()) return result;

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate to = cancelled.getDepartureDate().plusDays(WINDOW_DAYS);

        for (int i = 0; i < affectedBids.size(); i++) {
            BidEntity bid = affectedBids.get(i);
            CancellationEntity cancellation = cancellations.get(i);

            List<AnnouncementEntity> alternatives = findAlternatives(
                    cancelled, bid.getSenderId(), bid.getWeightKg(), today, to);

            for (AnnouncementEntity alt : alternatives) {
                RematchSuggestionEntity suggestion = new RematchSuggestionEntity();
                suggestion.setCancellationId(cancellation.getId());
                suggestion.setAnnouncementId(alt.getId());
                rematchSuggestionRepository.save(suggestion);
            }
            if (!alternatives.isEmpty()) {
                cancellation.setRematchStatus("SUGGESTED");
                cancellationRepository.save(cancellation);
            }
            result.put(bid.getSenderId(),
                    new RematchInfo(cancellation.getId(), alternatives.size()));
        }
        return result;
    }

    /**
     * Filtrage dur (statut/corridor/fenêtre de dates/capacité/visibilité publique/blocage)
     * fait en SQL via {@link Specification}. Tri (date croissante puis note voyageur
     * décroissante) et limite ({@link #MAX_SUGGESTIONS}) faits en mémoire ensuite, car la
     * note voyageur n'est pas dans announcements (nécessite un second aller-retour via
     * {@code UserRepository.findAllById}).
     */
    private List<AnnouncementEntity> findAlternatives(AnnouncementEntity cancelled,
                                                        UUID senderId, BigDecimal weightKg,
                                                        LocalDate from, LocalDate to) {
        Specification<AnnouncementEntity> spec = buildAlternativesSpec(cancelled, senderId, weightKg, from, to);

        List<AnnouncementEntity> candidates = announcementRepository.findAll(spec);

        Map<UUID, BigDecimal> ratings = userRepository
                .findAllById(candidates.stream()
                        .map(AnnouncementEntity::getTravelerId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(u -> u.getAverageRating() != null)
                .collect(Collectors.toMap(u -> u.getId(), u -> u.getAverageRating()));

        return candidates.stream()
                .sorted(Comparator
                        .comparing(AnnouncementEntity::getDepartureDate)
                        .thenComparing(a -> ratings.getOrDefault(a.getTravelerId(), BigDecimal.valueOf(-1)),
                                Comparator.reverseOrder()))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /**
     * Package-private (pas privée) pour être exercée directement contre une vraie DB par
     * {@code RematchSpecificationDbTest} — la construction seule ne prouve pas que le SQL
     * généré filtre correctement, il faut l'exécuter via {@code AnnouncementRepository}.
     * <p>
     * {@code weightKg} peut être {@code null} (bids en mode GRID — voir
     * {@code BidService.setWeightKg}, "peut être null pour GRID mode") : dans ce cas le
     * filtre {@code minAvailableKg} n'est PAS ajouté (pas de contrainte de poids connue),
     * plutôt que de passer {@code null} à {@code cb.greaterThanOrEqualTo(...)} qui lève une
     * {@code NullPointerException} à la construction du predicate — ce qui ferait échouer/
     * rollback toute la transaction {@code cancelTrip} dès qu'un bid GRID actif est affecté.
     */
    static Specification<AnnouncementEntity> buildAlternativesSpec(AnnouncementEntity cancelled,
                                                                     UUID senderId, BigDecimal weightKg,
                                                                     LocalDate from, LocalDate to) {
        Specification<AnnouncementEntity> spec = Specification
                .where(AnnouncementSpecification.hasStatus(AnnouncementStatus.ACTIVE))
                .and(AnnouncementSpecification.hasDepartureCity(cancelled.getDepartureCity()))
                .and(AnnouncementSpecification.hasArrivalCity(cancelled.getArrivalCity()))
                .and(AnnouncementSpecification.departureDateFrom(from))
                .and(AnnouncementSpecification.departureDateTo(to))
                .and(AnnouncementSpecification.publicOrOpenSurplus())
                .and(AnnouncementSpecification.handoverDeadlineNotPassed(
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)))
                .and(AnnouncementSpecification.notBlockedBy(senderId))
                .and((root, query, cb) -> cb.notEqual(root.get("id"), cancelled.getId()))
                .and((root, query, cb) -> cb.notEqual(root.get("travelerId"), cancelled.getTravelerId()));

        if (weightKg != null) {
            spec = spec.and(AnnouncementSpecification.minAvailableKg(weightKg));
        }
        return spec;
    }
}
