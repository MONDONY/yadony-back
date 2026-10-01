package com.yadony.api.addressbook.invitation.dto;

/** Corps de {@code POST /recipient-invitations} : exactement un des deux champs. */
public record CreateRecipientInvitationRequest(String phone, String email) {}
