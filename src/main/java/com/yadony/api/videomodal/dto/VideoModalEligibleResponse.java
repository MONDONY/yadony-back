package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalAudience;
import com.yadony.api.videomodal.VideoModalIcon;
import com.yadony.api.videomodal.VideoModalTrigger;

import java.util.List;
import java.util.UUID;

/** Modal éligible renvoyé à l'app, textes déjà dans la langue demandée. */
public record VideoModalEligibleResponse(
        UUID id,
        int version,
        List<VideoModalTrigger> triggers,
        String title,
        String subtitle,
        String youtubeVideoId,
        Integer durationSeconds,
        List<Highlight> highlights,
        String primaryLabel,
        String secondaryLabel,
        int maxDisplays,
        int displays,
        VideoModalAudience audience) {

    public record Highlight(VideoModalIcon icon, String text) {
    }
}
