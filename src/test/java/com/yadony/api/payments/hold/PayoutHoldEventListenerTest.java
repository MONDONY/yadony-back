package com.yadony.api.payments.hold;

import com.yadony.api.auth.events.UserBannedEvent;
import com.yadony.api.auth.events.UserReinstatedEvent;
import com.yadony.api.kyc.events.UserKycRevokedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PayoutHoldEventListenerTest {

    @Mock PayoutHoldService holds;
    @InjectMocks PayoutHoldEventListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @Test
    void bannissement_geleLesVersements() {
        listener.onUserBanned(new UserBannedEvent(userId, "fraude", adminId));
        verify(holds).hold(userId, PayoutHoldReason.BANNED, adminId);
    }

    @Test
    void revocationKyc_geleLesVersements() {
        listener.onKycRevoked(new UserKycRevokedEvent(userId, "document_fraud", adminId));
        verify(holds).hold(userId, PayoutHoldReason.KYC_REVOKED, adminId);
    }

    @Test
    void leveeDuBannissement_leveLeGelBanned() {
        listener.onUserReinstated(new UserReinstatedEvent(userId, adminId));
        verify(holds).release(userId, PayoutHoldReason.BANNED, adminId);
    }

    @Test
    void nouvelleVerificationKyc_leveLeGelKycRevoked() {
        listener.onKycVerified(new UserKycVerifiedEvent(userId));
        verify(holds).release(userId, PayoutHoldReason.KYC_REVOKED, null);
    }
}
