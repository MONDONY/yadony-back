package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalAction;
import com.yadony.api.videomodal.VideoModalAudience;
import com.yadony.api.videomodal.VideoModalIcon;
import com.yadony.api.videomodal.VideoModalImageFit;
import com.yadony.api.videomodal.VideoModalImageMode;
import com.yadony.api.videomodal.VideoModalLayout;
import com.yadony.api.videomodal.VideoModalTrigger;
import com.yadony.api.videomodal.VideoModalType;

import java.util.List;
import java.util.UUID;

/**
 * Modal éligible renvoyé à l'app, textes déjà dans la langue demandée. Pour un VIDEO,
 * {@code body}, {@code icon} et les actions sont null ; pour un INFO, {@code youtubeVideoId}
 * et {@code durationSeconds} sont null et {@code highlights} est vide.
 *
 * <p>{@code layout} et {@code sizePercent} valent pour les deux types. Les champs d'image
 * ({@code imageMode}, {@code imageFit}, {@code imageUrl}, {@code imageAlt}) ne sont renseignés
 * que pour INFO ; {@code imageUrl} est une URL présignée (le dépôt est privé).
 */
public record VideoModalEligibleResponse(
        UUID id,
        VideoModalType type,
        int version,
        List<VideoModalTrigger> triggers,
        String title,
        String subtitle,
        String youtubeVideoId,
        Integer durationSeconds,
        List<Highlight> highlights,
        String primaryLabel,
        String secondaryLabel,
        String body,
        VideoModalIcon icon,
        VideoModalAction primaryAction,
        VideoModalAction secondaryAction,
        int maxDisplays,
        int displays,
        VideoModalAudience audience,
        VideoModalLayout layout,
        Integer sizePercent,
        VideoModalImageMode imageMode,
        VideoModalImageFit imageFit,
        String imageUrl,
        String imageAlt) {

    public record Highlight(VideoModalIcon icon, String text) {
    }
}
