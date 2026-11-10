package com.yadony.api.videomodal;

/** Une situation d'une campagne : son genre et, selon le genre, un nombre d'heures ou de jours. */
public record VideoModalSituation(VideoModalSituationKind kind, Integer value) {
}
