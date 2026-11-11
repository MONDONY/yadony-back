package com.yadony.api.videomodal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Règles d'éligibilité propres à la personne et à la version courante d'une campagne : « ne plus
 * afficher », réponse, plafond d'affichages, fenêtre « tous les N jours », public. Pures (aucun
 * accès base) ; le ciblage fin (conditions, situations, tirage) est jugé ailleurs.
 */
final class VideoModalEligibility {

    private VideoModalEligibility() {
    }

    /** Ligne de la version courante dans les réponses de la personne, s'il y en a une. */
    static Optional<VideoModalResponseEntity> current(VideoModalCampaignEntity campaign,
                                                      List<VideoModalResponseEntity> responses) {
        int version = campaign.getVersion();
        return responses.stream().filter(r -> r.getVersion() == version).findFirst();
    }

    /** Équivaut à {@link #displaysIfEligible(VideoModalCampaignEntity, List, Instant)} à l'instant présent. */
    static Integer displaysIfEligible(VideoModalCampaignEntity campaign, List<VideoModalResponseEntity> responses) {
        return displaysIfEligible(campaign, responses, Instant.now());
    }

    /**
     * @param responses toutes les réponses de l'utilisateur à cette campagne (toutes versions)
     * @return le nombre d'affichages de la version courante si la campagne doit être proposée,
     *         {@code null} sinon
     */
    static Integer displaysIfEligible(VideoModalCampaignEntity campaign, List<VideoModalResponseEntity> responses,
                                      Instant now) {
        int version = campaign.getVersion();
        VideoModalResponseEntity current = current(campaign, responses).orElse(null);
        // « Ne plus afficher » : définitif pour la version, quelles que soient fréquence et réponse.
        if (current != null && current.isDontShowAgain()) return null;
        if (campaign.isStopAfterAnswer() && current != null && current.getStatus().isAnswer()) return null;
        int displays = current == null ? 0 : current.getDisplays();
        Integer cap = campaign.displayCap();
        if (cap != null && displays >= cap) return null;
        if (campaign.getFrequency() == VideoModalFrequency.EVERY_N_DAYS && current != null
                && current.getLastShownAt() != null
                && current.getLastShownAt().isAfter(now.minus(Duration.ofDays(campaign.getFrequencyDays())))) {
            return null;
        }

        List<VideoModalResponseEntity> previous = responses.stream()
                .filter(r -> r.getVersion() < version)
                .toList();
        // « Jamais répondu » ne regarde que la version juste avant (N-1) : qui a répondu à la v1 mais
        // pas à la v2 revoit la v3.
        List<VideoModalResponseEntity> immediatelyPrevious = responses.stream()
                .filter(r -> r.getVersion() == version - 1)
                .toList();
        boolean matches = switch (campaign.getAudience()) {
            case EVERYONE -> true;
            case NEVER_ANSWERED -> immediatelyPrevious.stream().noneMatch(r -> r.getStatus().isAnswer());
            case NOT_WATCHED_PREVIOUS -> previous.stream().noneMatch(r -> r.getStatus().isWatched());
        };
        return matches ? displays : null;
    }

    /**
     * Version et système de l'appareil. Une version ou un système inconnus (app ancienne qui ne les
     * envoie pas) ne satisfont aucune borne : une campagne restreinte n'est pas envoyée à l'aveugle.
     */
    static boolean matchesClient(VideoModalCampaignEntity c, int[] appVersion, VideoModalPlatform platform) {
        if (!c.getPlatforms().isEmpty() && (platform == null || !c.getPlatforms().contains(platform))) return false;
        Optional<int[]> min = VideoModalAppVersions.parseStrict(c.getMinAppVersion());
        if (min.isPresent() && (appVersion == null || VideoModalAppVersions.compare(appVersion, min.get()) < 0)) return false;
        Optional<int[]> max = VideoModalAppVersions.parseStrict(c.getMaxAppVersion());
        return max.isEmpty() || (appVersion != null && VideoModalAppVersions.compare(appVersion, max.get()) <= 0);
    }
}
