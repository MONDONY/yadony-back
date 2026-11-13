package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TravelerAnnouncementResponse(
    UUID id,
    String departureCity,
    String arrivalCity,
    LocalDate departureDate,
    BigDecimal pricePerKg,
    BigDecimal availableKg,
    String status,
    String currency,
    /** Le voyageur accepte les propositions de prix. */
    boolean negotiable,
    /** {@code KG} ou {@code MIXED} (grille) : un trajet à la grille seule a un prix au kilo à 0. */
    String pricingMode,
    /** Prix affiché expéditeur le plus bas de la grille (« dès X »), {@code null} hors grille. */
    BigDecimal minGridUnitPriceDisplay,
    /** Taille maximale d'un colis, route ; null = non renseignée. Toujours sérialisée. */
    @JsonInclude(JsonInclude.Include.ALWAYS) String maxItemSize,
    /** Poids maximal par colis en kg ; null = non renseigné. Toujours sérialisé. */
    @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal maxParcelWeightKg,
    /** Bateau : fin de la fourchette d'arrivée estimée ; null hors bateau. Toujours sérialisé. */
    @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate arrivalDateMax,
    /** Bateau : volume proposé en m³ ; null sans volume. Toujours sérialisé. */
    @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal volumeCapacityM3,
    /** Bateau : volume restant en m³ ; null sans volume. Toujours sérialisé. */
    @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal availableVolumeM3,
    /** Bateau au m³ : prix net voyageur au m³ (null pour un invité). Toujours sérialisé. */
    @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal pricePerM3,
    /** Prix au m³ payé par l'expéditeur (brut). Toujours sérialisé. */
    @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal pricePerM3Display
) {}
