package com.yadony.api.requests.event;

import java.util.UUID;

/** Publié dans la transaction de création d'une invitation ; le push part après commit. */
public record PackageRequestInvitationSentEvent(
        UUID invitationId,
        UUID packageRequestId,
        UUID announcementId,
        UUID senderId,
        UUID travelerId,
        String senderName,
        String departureCity,
        String arrivalCity) {}
