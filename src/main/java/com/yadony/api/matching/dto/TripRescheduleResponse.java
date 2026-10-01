package com.yadony.api.matching.dto;

import java.util.UUID;

/**
 * @param parcelsAwaitingDecision colis acceptés ou remis dont l'expéditeur doit répondre
 * @param requestsInformed        demandes pas encore acceptées, dont l'expéditeur est prévenu
 */
public record TripRescheduleResponse(
        UUID rescheduleId,
        int rescheduleCount,
        int remainingReschedules,
        int parcelsAwaitingDecision,
        int requestsInformed
) {}
