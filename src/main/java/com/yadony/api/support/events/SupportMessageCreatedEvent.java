package com.yadony.api.support.events;

import com.yadony.api.support.SupportMessageAuthorType;

import java.util.UUID;

/**
 * Publie a chaque message d'un fil support. Le package notifications l'ecoute
 * pour prevenir le proprietaire du ticket quand l'auteur est un admin.
 *
 * <p>{@code startedByAdmin} n'est vrai que pour le PREMIER message d'une
 * conversation ouverte par le support : l'utilisateur n'a rien demande, le push
 * doit donc annoncer un nouveau message (avec le sujet) et non « une reponse ».
 * {@code subject} n'est renseigne que dans ce cas.
 *
 * <p>{@code notifyOwner} faux : message enregistre sans push ni entree in-app. Seul
 * cas aujourd'hui, le message de CONTEXTE d'une conversation issue d'un signalement
 * (il reprend ce que l'utilisateur a ecrit) : la reponse de l'admin qui le suit porte
 * la seule notification.
 */
public class SupportMessageCreatedEvent {

    private final UUID ticketId;
    private final UUID messageId;
    private final UUID ownerUserId;
    private final SupportMessageAuthorType authorType;
    private final boolean startedByAdmin;
    private final String subject;
    private final boolean notifyOwner;

    public SupportMessageCreatedEvent(UUID ticketId, UUID messageId, UUID ownerUserId,
                                      SupportMessageAuthorType authorType) {
        this(ticketId, messageId, ownerUserId, authorType, false, null);
    }

    public SupportMessageCreatedEvent(UUID ticketId, UUID messageId, UUID ownerUserId,
                                      SupportMessageAuthorType authorType,
                                      boolean startedByAdmin, String subject) {
        this(ticketId, messageId, ownerUserId, authorType, startedByAdmin, subject, true);
    }

    public SupportMessageCreatedEvent(UUID ticketId, UUID messageId, UUID ownerUserId,
                                      SupportMessageAuthorType authorType,
                                      boolean startedByAdmin, String subject, boolean notifyOwner) {
        this.ticketId = ticketId;
        this.messageId = messageId;
        this.ownerUserId = ownerUserId;
        this.authorType = authorType;
        this.startedByAdmin = startedByAdmin;
        this.subject = subject;
        this.notifyOwner = notifyOwner;
    }

    public UUID getTicketId() { return ticketId; }

    public UUID getMessageId() { return messageId; }

    public UUID getOwnerUserId() { return ownerUserId; }

    public SupportMessageAuthorType getAuthorType() { return authorType; }

    public boolean isStartedByAdmin() { return startedByAdmin; }

    public String getSubject() { return subject; }

    public boolean isNotifyOwner() { return notifyOwner; }
}
