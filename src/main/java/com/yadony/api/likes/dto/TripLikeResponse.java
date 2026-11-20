package com.yadony.api.likes.dto;

/** État du j'aime d'un trajet pour l'appelant, renvoyé après {@code PUT} / {@code DELETE}. */
public record TripLikeResponse(long likeCount, boolean likedByMe) {}
