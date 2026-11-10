package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalAudience;
import com.yadony.api.videomodal.VideoModalIcon;
import com.yadony.api.videomodal.VideoModalTrigger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Campagne vue par l'admin, avec les statistiques de la version courante. */
public record VideoModalAdminResponse(
        UUID id,
        String name,
        String youtubeUrl,
        String youtubeVideoId,
        Integer durationSeconds,
        String titleFr,
        String titleEn,
        String subtitleFr,
        String subtitleEn,
        String primaryLabelFr,
        String primaryLabelEn,
        String secondaryLabelFr,
        String secondaryLabelEn,
        List<Highlight> highlights,
        List<VideoModalTrigger> triggers,
        int maxDisplays,
        Instant startsAt,
        Instant endsAt,
        boolean active,
        int version,
        VideoModalAudience audience,
        Instant versionPublishedAt,
        Instant createdAt,
        Instant updatedAt,
        Stats stats) {

    public record Highlight(VideoModalIcon icon, String textFr, String textEn) {
    }

    /** Utilisateurs distincts de la version courante. */
    public record Stats(long shown, long opened, long completed, long dismissed) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0);
    }
}
