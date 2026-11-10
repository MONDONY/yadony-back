package com.yadony.api.videomodal;

import java.util.List;

/** Règles d'éligibilité d'un utilisateur connu à la version courante d'une campagne. */
final class VideoModalEligibility {

    private VideoModalEligibility() {
    }

    /**
     * @param responses toutes les réponses de l'utilisateur à cette campagne (toutes versions)
     * @return le nombre d'affichages de la version courante si la campagne doit être proposée,
     *         {@code null} sinon
     */
    static Integer displaysIfEligible(VideoModalCampaignEntity campaign, List<VideoModalResponseEntity> responses) {
        int version = campaign.getVersion();
        VideoModalResponseEntity current = responses.stream()
                .filter(r -> r.getVersion() == version)
                .findFirst().orElse(null);
        if (current != null && current.getStatus().isAnswer()) return null;
        int displays = current == null ? 0 : current.getDisplays();
        if (displays >= campaign.getMaxDisplays()) return null;

        List<VideoModalResponseEntity> previous = responses.stream()
                .filter(r -> r.getVersion() < version)
                .toList();
        boolean matches = switch (campaign.getAudience()) {
            case EVERYONE -> true;
            case NEVER_ANSWERED -> previous.stream().noneMatch(r -> r.getStatus().isAnswer());
            case NOT_WATCHED_PREVIOUS -> previous.stream().noneMatch(r -> r.getStatus().isWatched());
        };
        return matches ? displays : null;
    }
}
