package com.yadony.api.matching;

import com.yadony.api.matching.ranking.TripFacts;
import com.yadony.api.payments.cash.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ligne légère d'une annonce candidate au classement : seulement les colonnes lues par le
 * score, le tri manuel, {@code fullLast} et les écarts du bloc 2. Lue par projection
 * ({@link AnnouncementRankingQueries}) pour ne jamais hydrater ni charger les collections des
 * centaines de candidats : seules les annonces affichées sont chargées en entités.
 */
public record RankingRow(UUID id, UUID travelerId, LocalDate departureDate, LocalDateTime createdAt,
                         Set<PaymentMethod> acceptedPaymentMethods, AnnouncementStatus status,
                         BigDecimal availableKg, CapacityUnit capacityUnit, TransportMode transportMode,
                         BigDecimal pricePerKgEur, Boolean travelerIsGpPro) {

    /** Même lecture qu'une entité chargée (tests et chemins sans projection). */
    public static RankingRow of(AnnouncementEntity a) {
        return new RankingRow(a.getId(), a.getTravelerId(), a.getDepartureDate(), a.getCreatedAt(),
                a.getAcceptedPaymentMethods(), a.getStatus(), a.getAvailableKg(), a.getCapacityUnit(),
                a.getTransportMode(), a.getPricePerKgEur(), a.isTravelerIsGpPro());
    }

    boolean gpPro() {
        return Boolean.TRUE.equals(travelerIsGpPro);
    }

    /** Faits lus par {@code SearchCriteriaMatcher}, avec les types de contenu si le filtre est actif. */
    TripFacts facts(List<String> acceptedContentTypes) {
        return new TripFacts(id, departureDate, availableKg, capacityUnit, transportMode, acceptedContentTypes);
    }
}
