package com.yadony.api.addressbook.invitation.dto;

/**
 * Corps de {@code POST /recipient-invitations} : exactement un de {@code phone}, {@code email}
 * et {@code username}. {@code name}, facultatif, est le nom que l'inviteur donne à la personne.
 */
public record CreateRecipientInvitationRequest(String phone, String email, String name, String username) {

    public CreateRecipientInvitationRequest(String phone, String email) {
        this(phone, email, null, null);
    }

    public CreateRecipientInvitationRequest(String phone, String email, String name) {
        this(phone, email, name, null);
    }
}
