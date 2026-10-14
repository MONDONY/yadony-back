package com.yadony.api.matching.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Trajet récurrent par dates précises : les autres dates du trajet d'origine, chacune
 * donnant un trajet. {@code saveAsDraft} : brouillons à publier plus tard, sinon publiés.
 */
public record TripSeriesRequest(
        @NotEmpty(message = "{validation.trip-series.dates.required}")
        @Size(max = 12, message = "{validation.trip-series.dates.max}")
        List<@NotNull LocalDate> dates,
        boolean saveAsDraft
) {}
