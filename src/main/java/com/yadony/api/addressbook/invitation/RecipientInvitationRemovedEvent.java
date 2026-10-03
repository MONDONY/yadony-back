package com.yadony.api.addressbook.invitation;

import java.util.UUID;

/** L'inviteur a retiré de son carnet un destinataire Yadony : l'invité doit en être prévenu. */
public record RecipientInvitationRemovedEvent(UUID invitationId, UUID inviterUserId, UUID inviteeUserId) {}
