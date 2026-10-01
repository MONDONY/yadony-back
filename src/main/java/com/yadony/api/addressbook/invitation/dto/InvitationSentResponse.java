package com.yadony.api.addressbook.invitation.dto;

/** Réponse unique de l'envoi, que le compte existe ou non : {@code { "status": "SENT" }}. */
public record InvitationSentResponse(String status) {

    public static final InvitationSentResponse SENT = new InvitationSentResponse("SENT");
}
