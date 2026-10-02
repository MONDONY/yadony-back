package com.yadony.api.addressbook.invitation.dto;

/**
 * Corps de {@code POST /recipient-invitations} : exactement un de {@code phone} et
 * {@code email}. {@code name}, facultatif, est le nom que l'inviteur donne à la personne.
 */
public record CreateRecipientInvitationRequest(String phone, String email, String name) {

    public CreateRecipientInvitationRequest(String phone, String email) {
        this(phone, email, null);
    }
}
