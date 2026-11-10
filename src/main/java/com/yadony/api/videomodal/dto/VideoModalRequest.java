package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalIcon;
import com.yadony.api.videomodal.VideoModalTrigger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Corps de création et de modification d'une campagne. L'URL YouTube et la période
 * (endsAt &gt; startsAt) sont contrôlées par le service, toujours en 422.
 */
public record VideoModalRequest(
        @Size(max = 120) String name,
        @NotBlank @Size(max = 500) String youtubeUrl,
        @Positive Integer durationSeconds,
        @NotBlank @Size(max = 120) String titleFr,
        @Size(max = 120) String titleEn,
        @Size(max = 300) String subtitleFr,
        @Size(max = 300) String subtitleEn,
        @Size(max = 60) String primaryLabelFr,
        @Size(max = 60) String primaryLabelEn,
        @Size(max = 60) String secondaryLabelFr,
        @Size(max = 60) String secondaryLabelEn,
        @Size(max = 3) List<@Valid @NotNull HighlightRequest> highlights,
        @NotEmpty List<@NotNull VideoModalTrigger> triggers,
        @Min(1) @Max(10) Integer maxDisplays,
        Instant startsAt,
        Instant endsAt) {

    public record HighlightRequest(
            @NotNull VideoModalIcon icon,
            @NotBlank @Size(max = 120) String textFr,
            @Size(max = 120) String textEn) {
    }
}
