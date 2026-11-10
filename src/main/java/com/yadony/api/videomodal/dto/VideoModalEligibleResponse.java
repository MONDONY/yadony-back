package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalAction;
import com.yadony.api.videomodal.VideoModalAudience;
import com.yadony.api.videomodal.VideoModalFrequency;
import com.yadony.api.videomodal.VideoModalIcon;
import com.yadony.api.videomodal.VideoModalImageFit;
import com.yadony.api.videomodal.VideoModalImageMode;
import com.yadony.api.videomodal.VideoModalLayout;
import com.yadony.api.videomodal.VideoModalTrigger;
import com.yadony.api.videomodal.VideoModalType;

import java.time.Instant;
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
 *
 * <p>Moment, ciblage et fréquence : {@code priority}, {@code frequency}, {@code frequencyDays},
 * {@code stopAfterAnswer}, {@code allowDontShowAgain} et {@code lastShownAt} (dernier affichage de
 * la personne pour la version courante, nul pour un visiteur ou si jamais montré). Une campagne à
 * situations et sans déclencheur est servie avec {@code triggers = [APP_OPEN]}.
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
        String imageAlt,
        int priority,
        VideoModalFrequency frequency,
        Integer frequencyDays,
        boolean stopAfterAnswer,
        boolean allowDontShowAgain,
        Instant lastShownAt) {

    /** Sans réglages de fréquence : jusqu'à N fois, s'arrête après une réponse (comportement d'avant). */
    public VideoModalEligibleResponse(UUID id, VideoModalType type, int version, List<VideoModalTrigger> triggers,
                                      String title, String subtitle, String youtubeVideoId, Integer durationSeconds,
                                      List<Highlight> highlights, String primaryLabel, String secondaryLabel,
                                      String body, VideoModalIcon icon, VideoModalAction primaryAction,
                                      VideoModalAction secondaryAction, int maxDisplays, int displays,
                                      VideoModalAudience audience, VideoModalLayout layout, Integer sizePercent,
                                      VideoModalImageMode imageMode, VideoModalImageFit imageFit, String imageUrl,
                                      String imageAlt) {
        this(id, type, version, triggers, title, subtitle, youtubeVideoId, durationSeconds, highlights,
                primaryLabel, secondaryLabel, body, icon, primaryAction, secondaryAction, maxDisplays, displays,
                audience, layout, sizePercent, imageMode, imageFit, imageUrl, imageAlt,
                0, VideoModalFrequency.UP_TO_N, null, true, false, null);
    }

    /** Même entrée avec les réglages de moment, de ciblage et de fréquence de la campagne. */
    public VideoModalEligibleResponse withTargeting(int priority, VideoModalFrequency frequency, Integer frequencyDays,
                                                    boolean stopAfterAnswer, boolean allowDontShowAgain,
                                                    Instant lastShownAt) {
        return new VideoModalEligibleResponse(id, type, version, triggers, title, subtitle, youtubeVideoId,
                durationSeconds, highlights, primaryLabel, secondaryLabel, body, icon, primaryAction,
                secondaryAction, maxDisplays, displays, audience, layout, sizePercent, imageMode, imageFit,
                imageUrl, imageAlt, priority, frequency, frequencyDays, stopAfterAnswer, allowDontShowAgain,
                lastShownAt);
    }

    public record Highlight(VideoModalIcon icon, String text) {
    }
}
