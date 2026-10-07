package com.yadony.api.messaging.dto;

/** Réponse de {@code POST /conversations/{id}/images} : identifiant du message Firestore écrit. */
public record ImageMessageResponse(String messageId) {}
