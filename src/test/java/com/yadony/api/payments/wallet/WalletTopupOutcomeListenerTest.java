package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletTopupOutcomeListenerTest {

    @Mock
    private PawapayOperationService operations;

    @Mock
    private WalletService walletService;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationDispatcher notifications;

    @InjectMocks
    private WalletTopupOutcomeListener listener;

    private PawapayOperationEntity topup(UUID userId) {
        return new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, userId, null, null, new BigDecimal("10000"), "XOF",
                "ORANGE_CIV", "CI", "+2250734567890");
    }

    @Test
    void completedWalletTopup_creditsWalletIdempotently() {
        UUID userId = UUID.randomUUID();
        PawapayOperationEntity op = topup(userId);
        when(operations.get(op.getId())).thenReturn(op);

        listener.onCompleted(new PawapayOperationCompletedEvent(op.getId(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, null, userId));

        verify(walletService).credit(userId, "XOF", new BigDecimal("10000"), WalletTransactionType.TOP_UP,
                "pawapay:" + op.getId(), "pawapay-topup-" + op.getId());
        verify(auditService).log(eq("wallet_topup"), eq(op.getId()), eq("MOBILE_MONEY_CONFIRMED"), eq(userId), any());
        // Le texte affiché rend exactement ce que produit WalletAmountText (espace insécable
        // de milliers pour XOF) : on récupère la chaîne attendue via ce même formateur plutôt
        // que de la coder en dur, pour ne pas dupliquer sa règle de rendu dans le test.
        String expectedAmount = WalletAmountText.format(new BigDecimal("10000"), "XOF");
        verify(notifications).notifyUser(eq(userId), eq("Recharge confirmée"),
                eq("Recharge de " + expectedAmount + " confirmée par Orange Money."), any());
    }

    @Test
    void completedBidDeposit_isIgnored() {
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.BID_PAYMENT, UUID.randomUUID(), null));
        verifyNoInteractions(walletService, operations);
    }

    @Test
    void completedPayout_isIgnoredEvenForWalletPurpose() {
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.WALLET_TOPUP, null, UUID.randomUUID()));
        verifyNoInteractions(walletService);
    }

    @Test
    void failedWalletTopup_creditsNothingAndAudits() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        listener.onFailed(new PawapayOperationFailedEvent(id, PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, null, userId, "PAYER_LIMIT_REACHED", "limit"));
        verifyNoInteractions(walletService);
        verify(auditService).log(eq("wallet_topup"), eq(id), eq("MOBILE_MONEY_FAILED"), eq(userId), any());
    }

    @Test
    void failedBidDeposit_isIgnored() {
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.BID_PAYMENT, UUID.randomUUID(), null, "CODE", "msg"));
        verifyNoInteractions(walletService, auditService, notifications);
    }

    @Test
    void failedPayout_isIgnoredEvenForWalletPurpose() {
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.WALLET_TOPUP, null, UUID.randomUUID(), "CODE", "msg"));
        verifyNoInteractions(walletService, auditService, notifications);
    }
}
