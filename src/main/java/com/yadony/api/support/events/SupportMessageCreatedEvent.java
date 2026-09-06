package com.yadony.api.support.events;

import com.yadony.api.support.SupportMessageAuthorType;

import java.util.UUID;

/**
 * Publie a chaque message d'un fil support. Le package notifications l'ecoute
 * pour prevenir le proprietaire du ticket quand l'auteur est un admin.
 */
public class SupportMessageCreatedEvent {

    private final UUID ticketId;
    private final UUID messageId;
    private final UUID ownerUserId;
    private final SupportMessageAuthorType authorType;

    public SupportMessageCreatedEvent(UUID ticketId, UUID messageId, UUID ownerUserId,
                                      SupportMessageAuthorType authorType) {
        this.ticketId = ticketId;
        this.messageId = messageId;
        this.ownerUserId = ownerUserId;
        this.authorType = authorType;
    }

    public UUID getTicketId() { return ticketId; }

    public UUID getMessageId() { return messageId; }

    public UUID getOwnerUserId() { return ownerUserId; }

    public SupportMessageAuthorType getAuthorType() { return authorType; }
}
