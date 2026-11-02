package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.matching.TransportMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Annonce telle que vue par un visiteur anonyme (recherche publique, lecture seule).
 *
 * <p>Liste blanche stricte, construite champ par champ depuis {@link AnnouncementSearchResponse} :
 * un champ ajouté plus tard à la réponse authentifiée ne fuite donc jamais ici. Jamais servis :
 * téléphone, e-mail, identifiant du voyageur, photo, adresses de remise, description libre, GPS,
 * prix net du voyageur ({@code pricePerKg}, {@code convertedPricePerKg}), taux de commission.
 * Le prix est {@code pricePerKgDisplay} (payé par l'expéditeur) dans {@code currency}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicAnnouncementCard(
        UUID id,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        BigDecimal availableKg,
        BigDecimal pricePerKgDisplay,
        String currency,
        TransportMode transportMode,
        boolean urgent,
        List<String> acceptedContentTypes,
        PublicTravelerCard traveler
) {

    public static PublicAnnouncementCard from(AnnouncementSearchResponse r) {
        return new PublicAnnouncementCard(
                r.id(),
                r.departureCity(),
                r.arrivalCity(),
                r.departureDate(),
                r.availableKg(),
                r.pricePerKgDisplay(),
                r.currency(),
                r.transportMode(),
                r.urgent(),
                r.acceptedContentTypes(),
                PublicTravelerCard.from(r.traveler()));
    }
}
