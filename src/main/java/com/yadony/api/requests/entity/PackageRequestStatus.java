package com.yadony.api.requests.entity;

public enum PackageRequestStatus {
    DRAFT, OPEN, NEGOTIATING, ACCEPTED, EXPIRED, CANCELLED, COMPLETED,
    /**
     * Retirée par la modération ({@code PackageRequestModerationService#removeByAdmin}).
     * Jamais listée publiquement ni proposée au matching ; le statut d'avant le retrait est
     * gardé dans {@code status_before_removal} pour la restauration. L'app expéditeur la
     * reçoit en {@code CANCELLED} + {@code moderationRemoved = true} (voir
     * {@code PackageRequestResponse}) : son parseur de statut plante sur une valeur inconnue.
     */
    REMOVED_BY_ADMIN
}
