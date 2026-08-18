package com.yadony.api.matching;

public enum AnnouncementStatus {
    DRAFT,
    ACTIVE,
    FULL,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    /** Retirée par un administrateur (modération). Restaurable vers ACTIVE. */
    REMOVED_BY_ADMIN
}
