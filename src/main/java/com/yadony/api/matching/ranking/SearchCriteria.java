package com.yadony.api.matching.ranking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Critères « souples » d'une recherche de trajets (ceux qu'on peut relâcher pour proposer
 * des trajets proches). Un critère {@code null} (ou faux) = aucune contrainte.
 *
 * @param idsInRadius identifiants des trajets dans le rayon ; {@code null} = pas de filtre rayon
 * @param urgentTo    borne haute de la fenêtre d'urgence, déjà calculée (aujourd'hui + seuil)
 */
public record SearchCriteria(LocalDate dateFrom, LocalDate dateTo,
                             BigDecimal minAvailableKg, BigDecimal maxAvailableKg,
                             String transportMode, BigDecimal minRating, String contentType,
                             Set<UUID> idsInRadius, boolean weekendOnly,
                             boolean urgent, LocalDate urgentTo, boolean kiloProOnly) {
}
