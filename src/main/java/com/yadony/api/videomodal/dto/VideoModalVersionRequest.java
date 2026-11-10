package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalAudience;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Nouvelle version : public choisi, et nouvelle vidéo facultative. */
public record VideoModalVersionRequest(
        @NotNull VideoModalAudience audience,
        @Size(max = 500) String youtubeUrl) {
}
