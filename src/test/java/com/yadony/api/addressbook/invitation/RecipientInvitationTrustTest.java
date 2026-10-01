package com.yadony.api.addressbook.invitation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientInvitationTrustTest {

    @Mock RecipientInvitationRepository repository;
    @InjectMocks RecipientInvitationTrust trust;

    @Test
    void trustedOnlyWithAcceptedInvitation() {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        when(repository.existsByInviterUserIdAndInviteeUserIdAndStatus(sender, recipient, InvitationStatus.ACCEPTED))
                .thenReturn(true);
        assertThat(trust.isTrusted(sender, recipient)).isTrue();
        assertThat(trust.isTrusted(recipient, sender)).isFalse();
    }

    @Test
    void nullIdsAreNeverTrusted() {
        assertThat(trust.isTrusted(null, UUID.randomUUID())).isFalse();
        assertThat(trust.isTrusted(UUID.randomUUID(), null)).isFalse();
        verifyNoInteractions(repository);
    }
}
