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
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
    @Mock org.springframework.transaction.PlatformTransactionManager transactionManager;

    UserFinalizedPaymentsListener listener;
    static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new UserFinalizedPaymentsListener(stripeCustomerService, userRepository,
                walletAccountRepository, walletSelfRefundService, walletService, transactionManager);
        when(userRepository.findByIdIncludingDeleted(USER_ID)).thenReturn(Optional.of(new UserEntity()));
    }

    private WalletAccountEntity wallet(String currency, String balance) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setUserId(USER_ID);
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    private static WalletRefundAllocation allocation(String refundable, String nonRefundable, String inFlight) {
        BigDecimal r = new BigDecimal(refundable);
        return new WalletRefundAllocation(List.of(), r,
                new BigDecimal(nonRefundable), new BigDecimal(inFlight), BigDecimal.ZERO, r);
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

    @Test
    void debitEchoue_nettoyageStripeQuandMeme() {
        when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(wallet("EUR", "5.00")));
        when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("0", "5.00", "0"));
        doThrow(new DataIntegrityViolationException("boom"))
                .when(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("5.00"),
                        WalletTransactionType.FORFEITED_ON_DELETION);

        listener.onUserFinalized(new UserFinalizedEvent(USER_ID, FinalizationReason.SOFT_GRACE_EXPIRED));

        verify(stripeCustomerService).cleanupForUser(any());
    }

    /**
     * Chaque devise dans sa propre transaction : un échec sur EUR n'annule que EUR, le forfait
     * XOF est committé. Avant, l'échec marquait la transaction commune rollback-only.
     */
    @Test
    void chaqueDeviseDansSaPropreTransaction_echecRollbackSeulementLaSienne() {
        when(walletAccountRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(wallet("EUR", "5.00"), wallet("XOF", "1000")));
        when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("0", "5.00", "0"));
        when(walletSelfRefundService.allocation(USER_ID, "XOF")).thenReturn(allocation("0", "1000", "0"));
        doThrow(new DataIntegrityViolationException("boom"))
                .when(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("5.00"),
                        WalletTransactionType.FORFEITED_ON_DELETION);
        org.springframework.transaction.TransactionStatus eurTx = mock(org.springframework.transaction.TransactionStatus.class);
        org.springframework.transaction.TransactionStatus xofTx = mock(org.springframework.transaction.TransactionStatus.class);
        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> definition =
                org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        when(transactionManager.getTransaction(definition.capture())).thenReturn(eurTx, xofTx);

        listener.onUserFinalized(new UserFinalizedEvent(USER_ID, FinalizationReason.SOFT_GRACE_EXPIRED));

        assertThat(definition.getAllValues()).allSatisfy(d -> assertThat(d.getPropagationBehavior())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactionManager).rollback(eurTx);
        verify(transactionManager).commit(xofTx);
        verify(transactionManager, never()).commit(eurTx);
    }

    @Test
    void debitEchoueSurUneDevise_autreDeviseQuandMemeTentee() {
        when(walletAccountRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(wallet("EUR", "5.00"), wallet("XOF", "1000")));
        when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("0", "5.00", "0"));
        when(walletSelfRefundService.allocation(USER_ID, "XOF")).thenReturn(allocation("0", "1000", "0"));
        doThrow(new DataIntegrityViolationException("boom"))
                .when(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("5.00"),
                        WalletTransactionType.FORFEITED_ON_DELETION);

        listener.onUserFinalized(new UserFinalizedEvent(USER_ID, FinalizationReason.SOFT_GRACE_EXPIRED));

        verify(walletService).debitConfirmedRefund(eq(USER_ID), eq("XOF"), any(), any());
        verify(stripeCustomerService).cleanupForUser(any());
    }
}
