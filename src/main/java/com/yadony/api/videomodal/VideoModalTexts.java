package com.yadony.api.videomodal;

import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;

import java.util.List;

/** Choix de la langue des textes d'une campagne, avec repli sur le français. */
final class VideoModalTexts {

    private VideoModalTexts() {
    }

    static VideoModalEligibleResponse toEligible(VideoModalCampaignEntity c, boolean english, int displays) {
        if (c.getType() == VideoModalType.INFO) {
            return toEligibleInfo(c, english, displays);
        }
        return new VideoModalEligibleResponse(
                c.getId(),
                VideoModalType.VIDEO,
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
                null, null, null, null,
                c.getMaxDisplays(),
                displays,
                c.getAudience());
    }

    private static VideoModalEligibleResponse toEligibleInfo(VideoModalCampaignEntity c, boolean english, int displays) {
        VideoModalAction secondary = c.getSecondaryAction();
        return new VideoModalEligibleResponse(
                c.getId(),
                VideoModalType.INFO,
                c.getVersion(),
                c.getTriggers(),
                pick(english, c.getTitleFr(), c.getTitleEn()),
                pick(english, c.getSubtitleFr(), c.getSubtitleEn()),
                null,
                null,
                List.of(),
                actionLabel(english, c.getPrimaryAction(), c.getPrimaryLabelFr(), c.getPrimaryLabelEn()),
                secondary == null ? null
                        : actionLabel(english, secondary, c.getSecondaryLabelFr(), c.getSecondaryLabelEn()),
                pick(english, c.getBodyFr(), c.getBodyEn()),
                c.getIcon() == null ? VideoModalIcon.INFO : c.getIcon(),
                c.getPrimaryAction(),
                secondary,
                c.getMaxDisplays(),
                displays,
                c.getAudience());
    }

    /** Libellé du bouton ; « OK » par défaut pour CLOSE, jamais de défaut pour SCREEN et URL. */
    private static String actionLabel(boolean english, VideoModalAction action, String fr, String en) {
        String label = pick(english, fr, en);
        if (label != null) return label;
        return action != null && action.kind() == VideoModalActionKind.CLOSE ? "OK" : null;
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
