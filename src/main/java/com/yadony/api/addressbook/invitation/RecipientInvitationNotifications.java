package com.yadony.api.addressbook.invitation;

/** Types de notification ({@code data["type"]}) des invitations au carnet. */
public final class RecipientInvitationNotifications {

    private RecipientInvitationNotifications() {}

    /** À l'invité : un expéditeur veut l'ajouter à ses destinataires. */
    public static final String INVITATION = "RECIPIENT_INVITATION";
    /** À l'inviteur : son invitation est acceptée. */
    public static final String ACCEPTED = "RECIPIENT_INVITATION_ACCEPTED";
}
