package com.yadony.api.videomodal;

import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;

/** Choix de la langue des textes d'une campagne, avec repli sur le français. */
final class VideoModalTexts {

    private VideoModalTexts() {
    }

    static VideoModalEligibleResponse toEligible(VideoModalCampaignEntity c, boolean english, int displays) {
        return new VideoModalEligibleResponse(
                c.getId(),
                c.getVersion(),
                c.getTriggers(),
                pick(english, c.getTitleFr(), c.getTitleEn()),
                pick(english, c.getSubtitleFr(), c.getSubtitleEn()),
                c.getYoutubeVideoId(),
                c.getDurationSeconds(),
                c.getHighlights().stream()
                        .map(h -> new VideoModalEligibleResponse.Highlight(h.icon(), pick(english, h.textFr(), h.textEn())))
                        .toList(),
                orDefault(pick(english, c.getPrimaryLabelFr(), c.getPrimaryLabelEn()),
                        defaultPrimaryLabel(english, c.getDurationSeconds())),
                orDefault(pick(english, c.getSecondaryLabelFr(), c.getSecondaryLabelEn()),
                        english ? "Get started" : "Commencer"),
                c.getMaxDisplays(),
                displays,
                c.getAudience());
    }

    /** Texte anglais s'il est demandé et rempli, sinon le français. */
    static String pick(boolean english, String fr, String en) {
        if (english && en != null && !en.isBlank()) return en;
        return fr == null || fr.isBlank() ? null : fr;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** « Voir la vidéo (3 min) » quand la durée est connue, « Voir la vidéo » sinon. */
    static String defaultPrimaryLabel(boolean english, Integer durationSeconds) {
        String base = english ? "Watch the video" : "Voir la vidéo";
        if (durationSeconds == null) return base;
        long minutes = Math.max(1, Math.round(durationSeconds / 60.0));
        return base + " (" + minutes + " min)";
    }
}
