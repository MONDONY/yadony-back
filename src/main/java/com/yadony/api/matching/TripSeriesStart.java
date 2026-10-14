package com.yadony.api.matching;

import com.yadony.api.matching.dto.AnnouncementRequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Trajet d'origine d'une série, lu dans sa transaction : la requête de création qui le
 * reproduit à l'identique ({@code base}, à décaler par date), sa date de départ et le
 * fuseau de sa ville de départ.
 */
public record TripSeriesStart(UUID seriesId, LocalDate sourceDate, ZoneId zone, AnnouncementRequest base) {}
