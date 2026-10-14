package com.yadony.api.matching.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Résultat d'une série : un trajet par date acceptée, et pour chaque date refusée le code
 * métier du refus (quota de brouillons, KYC, date passée, date déjà prise…). Une date
 * refusée n'empêche jamais les autres.
 */
public record TripSeriesResponse(UUID seriesId, List<AnnouncementResponse> created, List<Failure> failed) {

    public record Failure(LocalDate date, String code, String detail) {}
}
