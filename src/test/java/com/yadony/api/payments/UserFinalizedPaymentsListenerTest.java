package com.yadony.api.payments;

import com.yadony.api.auth.FinalizationReason;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserFinalizedEvent;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletAllocationInvariantException;
import com.yadony.api.payments.wallet.WalletRefundAllocation;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserFinalizedPaymentsListenerTest {

    @Mock StripeCustomerService stripeCustomerService;
    @Mock UserRepository userRepository;
    @Mock WalletAccountRepository walletAccountRepository;
    @Mock WalletSelfRefundService walletSelfRefundService;
    @Mock WalletService walletService;

    UserFinalizedPaymentsListener listener;
    static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new UserFinalizedPaymentsListener(stripeCustomerService, userRepository,
                walletAccountRepository, walletSelfRefundService, walletService);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new UserEntity()));
    }

    private WalletAccountEntity wallet(String currency, String balance) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setUserId(USER_ID);
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    private static WalletRefundAllocation allocation(String refundable, String nonRefundable, String inFlight) {
        return new WalletRefundAllocation(List.of(), new BigDecimal(refundable),
                new BigDecimal(nonRefundable), new BigDecimal(inFlight));
    }

    @Test
    void nonCash_debiteEnForfeited() {
        when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(wallet("EUR", "5.00")));
        when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("0", "5.00", "0"));

        listener.onUserFinalized(new UserFinalizedEvent(USER_ID, FinalizationReason.SOFT_GRACE_EXPIRED));

        verify(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.FORFEITED_ON_DELETION);
        verify(stripeCustomerService).cleanupForUser(any());
    }

    @Test
    void cashEnCoursOuEchoue_jamaisForfeited() {
        when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(wallet("EUR", "40.00")));
        when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("0", "0", "40.00"));

        listener.onUserFinalized(new UserFinalizedEvent(USER_ID, FinalizationReason.HARD_IMMEDIATE));

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    @Test
    void secondPassage_rienADebiter() {
        when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(wallet("EUR", "0.00")));

        listener.onUserFinalized(new UserFinalizedEvent(USER_ID, FinalizationReason.SOFT_GRACE_EXPIRED));

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    @Test
    void invariantCasse_ignoreSansPlanter() {
        when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(wallet("EUR", "40.00")));
        when(walletSelfRefundService.allocation(USER_ID, "EUR"))
                .thenThrow(new WalletAllocationInvariantException(BigDecimal.ONE, BigDecimal.TEN));

        listener.onUserFinalized(new UserFinalizedEvent(USER_ID, FinalizationReason.SOFT_GRACE_EXPIRED));

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
        verify(stripeCustomerService).cleanupForUser(any());
    }
}
