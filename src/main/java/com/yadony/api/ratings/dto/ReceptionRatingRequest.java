package com.yadony.api.ratings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Note du voyageur par le destinataire confirmé, depuis son compte (FLUTTER-CA). */
public record ReceptionRatingRequest(
        @NotNull @Min(1) @Max(5) Integer stars,
        @Size(max = 200) String comment
) {}
