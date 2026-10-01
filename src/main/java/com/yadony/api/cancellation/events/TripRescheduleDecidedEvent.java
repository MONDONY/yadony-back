package com.yadony.api.cancellation.events;

import com.yadony.api.cancellation.RescheduleDecision;

import java.util.UUID;

/** L'expéditeur a répondu au report du trajet : le voyageur en est prévenu. */
public record TripRescheduleDecidedEvent(
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        RescheduleDecision decision
) {}
