package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.PricingMode;
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
 *
 * <p><b>Le client lit {@code capacityUnit} avant d'afficher des kilos.</b> {@code availableKg}
 * n'a de sens que pour une capacité au poids : en {@link CapacityUnit#KG_FREE} (kilo libre, non
 * borné) c'est une valeur technique (≈ 1) qu'il ne faut jamais afficher comme « 1 kg disponible ».
 * {@code pricingMode} explique un prix absent : un trajet {@link PricingMode#MIXED} à grille
 * seule n'a pas de prix au kilo (la grille, elle, n'est pas servie ici).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicAnnouncementCard(
        UUID id,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        BigDecimal availableKg,
        CapacityUnit capacityUnit,
        BigDecimal pricePerKgDisplay,
        String currency,
        PricingMode pricingMode,
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
                r.capacityUnit(),
                displayablePrice(r.pricePerKgDisplay()),
                r.currency(),
                r.pricingMode(),
                r.transportMode(),
                r.urgent(),
                // Copie : la source est un PersistentBag Hibernate tenu dans une valeur de cache partagée.
                r.acceptedContentTypes() == null ? null : List.copyOf(r.acceptedContentTypes()),
                PublicTravelerCard.from(r.traveler()));
    }

    /**
     * Un trajet à grille seule (mode MIXED) est stocké avec 0 au kilo (contrainte V94) : le servir
     * afficherait « 0 €/kg ». Un prix nul ou négatif est donc traité comme absent.
     */
    private static BigDecimal displayablePrice(BigDecimal price) {
        return price != null && price.signum() > 0 ? price : null;
    }
}
