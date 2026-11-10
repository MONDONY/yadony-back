package com.yadony.api.videomodal.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.videomodal.VideoModalAudience;
import com.yadony.api.videomodal.VideoModalFrequency;
import com.yadony.api.videomodal.VideoModalIcon;
import com.yadony.api.videomodal.VideoModalImageFit;
import com.yadony.api.videomodal.VideoModalImageMode;
import com.yadony.api.videomodal.VideoModalLayout;
import com.yadony.api.videomodal.VideoModalPlatform;
import com.yadony.api.videomodal.VideoModalType;
import com.yadony.api.videomodal.VideoModalTrigger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
 *
 * <p>Moment, ciblage et fréquence (tous facultatifs, défauts = comportement d'avant) :
 * {@code frequency} (UP_TO_N), {@code frequencyDays} (1 à 365, obligatoire pour EVERY_N_DAYS),
 * {@code stopAfterAnswer} (true), {@code allowDontShowAgain} (false), {@code priority} (0),
 * {@code connectedOnly} (false), {@code conditions} (arbre de règles de la Diffusion, peut être vide),
 * {@code minAppVersion}/{@code maxAppVersion} ({@code x.y.z}), {@code platforms} (vide = toutes),
 * {@code samplePercent} (1 à 100, défaut 100) et {@code situations}. Les déclencheurs peuvent être
 * vides seulement s'il y a des situations (la campagne est alors servie avec {@code APP_OPEN}). {@code audience}
 * (facultatif) : public de la version courante ; sans lui, EVERYONE pour INFO et NEVER_ANSWERED pour VIDEO à la
 * création, inchangé à la modification. {@code priority} : -1000 à 1000.
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
        List<@NotNull VideoModalTrigger> triggers,
        @Min(1) @Max(10) Integer maxDisplays,
        Instant startsAt,
        Instant endsAt,
        VideoModalLayout layout,
        Integer sizePercent,
        VideoModalImageMode imageMode,
        VideoModalImageFit imageFit,
        @Size(max = 200) String imageAltFr,
        @Size(max = 200) String imageAltEn,
        VideoModalFrequency frequency,
        Integer frequencyDays,
        Boolean stopAfterAnswer,
        Boolean allowDontShowAgain,
        Integer priority,
        Boolean connectedOnly,
        JsonNode conditions,
        String minAppVersion,
        String maxAppVersion,
        List<VideoModalPlatform> platforms,
        Integer samplePercent,
        List<SituationRequest> situations,
        VideoModalAudience audience) {

    /** Sans {@code audience} : défaut selon le type à la création (EVERYONE pour INFO, NEVER_ANSWERED pour VIDEO), inchangé à la modification. */
    public VideoModalRequest(String name, VideoModalType type, String youtubeUrl, Integer durationSeconds,
                             String titleFr, String titleEn, String subtitleFr, String subtitleEn,
                             String primaryLabelFr, String primaryLabelEn, String secondaryLabelFr,
                             String secondaryLabelEn, String bodyFr, String bodyEn, VideoModalIcon icon,
                             ActionRequest primaryAction, ActionRequest secondaryAction,
                             List<HighlightRequest> highlights, List<VideoModalTrigger> triggers,
                             Integer maxDisplays, Instant startsAt, Instant endsAt, VideoModalLayout layout,
                             Integer sizePercent, VideoModalImageMode imageMode, VideoModalImageFit imageFit,
                             String imageAltFr, String imageAltEn, VideoModalFrequency frequency,
                             Integer frequencyDays, Boolean stopAfterAnswer, Boolean allowDontShowAgain,
                             Integer priority, Boolean connectedOnly, JsonNode conditions, String minAppVersion,
                             String maxAppVersion, List<VideoModalPlatform> platforms, Integer samplePercent,
                             List<SituationRequest> situations) {
        this(name, type, youtubeUrl, durationSeconds, titleFr, titleEn, subtitleFr, subtitleEn, primaryLabelFr,
                primaryLabelEn, secondaryLabelFr, secondaryLabelEn, bodyFr, bodyEn, icon, primaryAction,
                secondaryAction, highlights, triggers, maxDisplays, startsAt, endsAt, layout, sizePercent,
                imageMode, imageFit, imageAltFr, imageAltEn, frequency, frequencyDays, stopAfterAnswer,
                allowDontShowAgain, priority, connectedOnly, conditions, minAppVersion, maxAppVersion,
                platforms, samplePercent, situations, null);
    }

    /**
     * Sans réglages de moment, de ciblage ni de fréquence : « jusqu'à N fois », s'arrête après une
     * réponse, aucune condition (le corps d'avant l'étape 1, toujours accepté).
     */
    public VideoModalRequest(String name, VideoModalType type, String youtubeUrl, Integer durationSeconds,
                             String titleFr, String titleEn, String subtitleFr, String subtitleEn,
                             String primaryLabelFr, String primaryLabelEn, String secondaryLabelFr,
                             String secondaryLabelEn, String bodyFr, String bodyEn, VideoModalIcon icon,
                             ActionRequest primaryAction, ActionRequest secondaryAction,
                             List<HighlightRequest> highlights, List<VideoModalTrigger> triggers,
                             Integer maxDisplays, Instant startsAt, Instant endsAt, VideoModalLayout layout,
                             Integer sizePercent, VideoModalImageMode imageMode, VideoModalImageFit imageFit,
                             String imageAltFr, String imageAltEn) {
        this(name, type, youtubeUrl, durationSeconds, titleFr, titleEn, subtitleFr, subtitleEn, primaryLabelFr,
                primaryLabelEn, secondaryLabelFr, secondaryLabelEn, bodyFr, bodyEn, icon, primaryAction,
                secondaryAction, highlights, triggers, maxDisplays, startsAt, endsAt, layout, sizePercent,
                imageMode, imageFit, imageAltFr, imageAltEn,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Situation constatée par le serveur. {@code kind} reste une chaîne : un genre inconnu est refusé
     * en 422 avec un message clair, comme les actions.
     */
    public record SituationRequest(String kind, Integer value) {
    }

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
