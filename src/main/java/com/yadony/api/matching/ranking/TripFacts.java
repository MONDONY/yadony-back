package com.yadony.api.matching.ranking;

import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Faits d'un trajet lus par {@link SearchCriteriaMatcher} : de quoi calculer les écarts sans
 * charger l'entité. {@code acceptedContentTypes} n'est lu que si la recherche filtre sur un type
 * de contenu ; ailleurs il peut rester vide.
 */
public record TripFacts(UUID id, LocalDate departureDate, BigDecimal availableKg, CapacityUnit capacityUnit,
                        TransportMode transportMode, List<String> acceptedContentTypes) {
}
