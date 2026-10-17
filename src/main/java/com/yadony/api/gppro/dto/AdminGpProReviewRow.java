package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProReviewReason;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Une personne à réexaminer ; {@code flags} porte les identifiants pour écarter un motif. */
public record AdminGpProReviewRow(UUID userId, String displayName, List<GpProReviewReason> reasons,
                                  LocalDateTime detectedAt, List<Flag> flags) {

    public record Flag(UUID flagId, GpProReviewReason reason) {
    }
}
