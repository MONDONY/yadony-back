package com.yadony.api.settings;

import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Lot 2 (2026-08-19) — devise figée dès le premier mouvement d'argent :
 * portefeuille non vide, transaction portefeuille passée, ou envoi engagé.
 */
@ExtendWith(MockitoExtension.class)
class CurrencyLockServiceTest {

    @Mock WalletAccountRepository walletAccountRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock BidRepository bidRepository;
    @InjectMocks CurrencyLockService service;

    private final UUID userId = UUID.randomUUID();

    private void stubNothingLocking() {
        lenient().when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of());
        lenient().when(walletTransactionRepository.existsByUserId(userId)).thenReturn(false);
        lenient().when(bidRepository.existsBySenderIdAndStatusIn(eq(userId), anyList())).thenReturn(false);
    }

    @Test
    void isLocked_noWalletNoTransactionNoBid_returnsFalse() {
        stubNothingLocking();

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    void isLocked_walletBalancePositive_returnsTrue() {
        stubNothingLocking();
        WalletAccountEntity account = new WalletAccountEntity();
        account.setUserId(userId);
        account.setCurrency("EUR");
        account.setBalance(new BigDecimal("12.50"));
        when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of(account));

        assertThat(service.isLocked(userId)).isTrue();
    }

    @Test
    void isLocked_walletBalanceZeroInEveryCurrency_returnsFalse() {
        stubNothingLocking();
        WalletAccountEntity account = new WalletAccountEntity();
        account.setUserId(userId);
        account.setCurrency("EUR");
        account.setBalance(BigDecimal.ZERO);
        when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of(account));

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    void isLocked_atLeastOneWalletTransactionEverRecorded_returnsTrue() {
        stubNothingLocking();
        when(walletTransactionRepository.existsByUserId(userId)).thenReturn(true);

        assertThat(service.isLocked(userId)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {
            "PAYMENT_ESCROWED", "ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"
    })
    void isLocked_bidEngagedAsSender_returnsTrue(BidStatus status) {
        stubNothingLocking();
        when(bidRepository.existsBySenderIdAndStatusIn(eq(userId), anyList())).thenAnswer(inv -> {
            List<?> statuses = inv.getArgument(1);
            return statuses.contains(status);
        });

        assertThat(service.isLocked(userId)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {
            "AWAITING_PAYMENT", "PENDING", "REJECTED", "CANCELLED", "COMPLETED",
            "NO_SHOW", "PARCEL_REFUSED", "EXPIRED", "NEGOTIATING", "NEGOTIATION_CLOSED"
    })
    void isLocked_bidNotYetCommittedOrTerminal_doesNotLockOnItsOwn(BidStatus status) {
        stubNothingLocking();

        assertThat(service.isLocked(userId)).isFalse();
    }
}
