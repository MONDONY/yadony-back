package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalIcon;
import com.yadony.api.videomodal.VideoModalImageFit;
import com.yadony.api.videomodal.VideoModalImageMode;
import com.yadony.api.videomodal.VideoModalLayout;
import com.yadony.api.videomodal.VideoModalType;
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
 * (endsAt &gt; startsAt) sont contrôlées par le service, toujours en 422, comme tout ce qui
 * dépend du type : VIDEO exige un lien YouTube valide ; INFO exige un texte FR (1 à 500
 * caractères) et une action principale, et ignore lien, durée et points forts.
 *
 * <p>Format et image : {@code layout} (défaut BOTTOM_SHEET), {@code sizePercent} (30 à 100 ou
 * null = automatique, contrôlé par le service en 422), {@code imageMode} (défaut NONE, forcé
 * NONE pour VIDEO ; autre que NONE exige une image déjà envoyée), {@code imageFit} (défaut
 * COVER) et textes alternatifs. L'image elle-même passe par {@code POST …/{id}/image}.
 */
public record VideoModalRequest(
        @Size(max = 120) String name,
        VideoModalType type,
        @Size(max = 500) String youtubeUrl,
        @Positive Integer durationSeconds,
        @NotBlank @Size(max = 120) String titleFr,
        @Size(max = 120) String titleEn,
        @Size(max = 300) String subtitleFr,
        @Size(max = 300) String subtitleEn,
        @Size(max = 60) String primaryLabelFr,
        @Size(max = 60) String primaryLabelEn,
        @Size(max = 60) String secondaryLabelFr,
        @Size(max = 60) String secondaryLabelEn,
        String bodyFr,
        String bodyEn,
        VideoModalIcon icon,
        @Valid ActionRequest primaryAction,
        @Valid ActionRequest secondaryAction,
        @Size(max = 3) List<@Valid @NotNull HighlightRequest> highlights,
        @NotEmpty List<@NotNull VideoModalTrigger> triggers,
        @Min(1) @Max(10) Integer maxDisplays,
        Instant startsAt,
        Instant endsAt,
        VideoModalLayout layout,
        Integer sizePercent,
        VideoModalImageMode imageMode,
        VideoModalImageFit imageFit,
        @Size(max = 200) String imageAltFr,
        @Size(max = 200) String imageAltEn) {

    /**
     * Action d'un bouton INFO. {@code kind} et {@code screen} restent des chaînes pour qu'une
     * valeur inconnue soit refusée en 422 (et non en 400) avec un message clair.
     */
    public record ActionRequest(String kind, String screen, String url) {
    }

    public record HighlightRequest(
            @NotNull VideoModalIcon icon,
            @NotBlank @Size(max = 120) String textFr,
            @Size(max = 120) String textEn) {
    }
}
