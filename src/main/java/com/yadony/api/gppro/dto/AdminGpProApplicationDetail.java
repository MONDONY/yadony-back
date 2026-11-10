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
        List<Doc> documents,
        /** Numéro d'entreprise et pays de la demande (formulaire unifié) ; nuls pour une ancienne demande. */
        String businessRegistrationNumber,
        String businessCountry
) {
    /** Sans données d'entreprise. */
    public AdminGpProApplicationDetail(
            UUID applicationId, UUID userId, String displayName, GpProApplicationStatus status,
            GpProApplicationSource source, GpProTravelFrequency travelFrequency, String commercialName,
            LocalDateTime createdAt, String kycStatus, long publishedTrips, long tripsLast56Days,
            long completedDeliveries, BigDecimal averageRating, List<Integer> lastFiveRatings, long openDisputes,
            long openReports, LocalDateTime memberSince, List<Doc> documents) {
        this(applicationId, userId, displayName, status, source, travelFrequency, commercialName, createdAt,
                kycStatus, publishedTrips, tripsLast56Days, completedDeliveries, averageRating, lastFiveRatings,
                openDisputes, openReports, memberSince, documents, null, null);
    }

    public record Doc(UUID id, GpProDocumentType type, String url) {
    }
}
