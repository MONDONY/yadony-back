package com.yadony.api.addressbook.invitation;

import java.util.UUID;

/** Une invitation vise un compte existant : son titulaire doit être prévenu. */
public record RecipientInvitationCreatedEvent(UUID invitationId, UUID inviterUserId, UUID inviteeUserId) {}
