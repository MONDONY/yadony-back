package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProDocumentType;
import com.yadony.api.gppro.GpProTravelFrequency;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code state} : NONE, AWAITING_KYC, PENDING, ACTIVE, REJECTED ou REVOKED.
 *
 * <p>{@code documents} liste les justificatifs non purgés de la demande ouverte (vide sinon) :
 * identifiant et type seulement, jamais d'URL ni de clé de stockage.
 */
public record GpProStatusResponse(String state, GpProTravelFrequency travelFrequency, String commercialName,
                                  int documentCount, String reason, LocalDateTime decidedAt,
                                  List<Document> documents) {

    public record Document(UUID id, GpProDocumentType type) {
    }

    /** Sans justificatifs listés. */
    public GpProStatusResponse(String state, GpProTravelFrequency travelFrequency, String commercialName,
                               int documentCount, String reason, LocalDateTime decidedAt) {
        this(state, travelFrequency, commercialName, documentCount, reason, decidedAt, List.of());
    }
}
