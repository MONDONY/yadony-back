package com.yadony.api.videomodal.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.videomodal.VideoModalPlatform;

import java.util.List;

/**
 * Corps de {@code POST /admin/video-modals/audience-estimate} : le ciblage d'une campagne en cours de
 * saisie. Mêmes règles de validation que la création (422).
 */
public record AudienceEstimateRequest(
        JsonNode conditions,
        Boolean connectedOnly,
        List<VideoModalPlatform> platforms,
        Integer samplePercent,
        String minAppVersion,
        String maxAppVersion) {
}
