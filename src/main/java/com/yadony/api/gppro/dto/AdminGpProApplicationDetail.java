package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProApplicationSource;
import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProDocumentType;
import com.yadony.api.gppro.GpProTravelFrequency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fiche d'une demande pour la décision de l'admin : la demande, le profil du demandeur et les
 * justificatifs non purgés (URL présignée de 15 minutes).
 */
public record AdminGpProApplicationDetail(
        UUID applicationId,
        UUID userId,
        String displayName,
        GpProApplicationStatus status,
        GpProApplicationSource source,
        GpProTravelFrequency travelFrequency,
        String commercialName,
        LocalDateTime createdAt,
        String kycStatus,
        long publishedTrips,
        long tripsLast56Days,
        long completedDeliveries,
        BigDecimal averageRating,
        List<Integer> lastFiveRatings,
        long openDisputes,
        long openReports,
        LocalDateTime memberSince,
        List<Doc> documents
) {
    public record Doc(UUID id, GpProDocumentType type, String url) {
    }
}
