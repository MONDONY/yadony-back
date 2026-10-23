package com.yadony.api.admin.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Photos d'un colis rangées par étape de sa vie. Les URL sont signées à la demande (15 min), jamais
 * stockées. {@code stage} : {@code CREATION}, {@code DEPART}, {@code TRANSIT}, {@code ARRIVEE},
 * {@code REFUSAL} ou {@code DISPUTE}.
 */
public record AdminBidPhotosResponse(UUID bidId, List<Photo> photos) {

    public record Photo(String id, String stage, String label, LocalDateTime at, String url, String gpsLabel) {
    }
}
