package com.yadony.api.addressbook.invitation;

import com.yadony.api.common.RecipientTrust;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Confiance établie par une invitation ACCEPTED de l'expéditeur vers le destinataire. */
@Component
public class RecipientInvitationTrust implements RecipientTrust {

    private final RecipientInvitationRepository repository;

    public RecipientInvitationTrust(RecipientInvitationRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isTrusted(UUID senderId, UUID recipientUserId) {
        if (senderId == null || recipientUserId == null) {
            return false;
        }
        return repository.existsByInviterUserIdAndInviteeUserIdAndStatus(
                senderId, recipientUserId, InvitationStatus.ACCEPTED);
    }
}
