package com.yadony.api.videomodal.dto;

/** Comptes concernés par un ciblage, puis après tirage au sort : des nombres, jamais de donnée personnelle. */
public record AudienceEstimateResponse(long accounts, long afterSample) {
}
