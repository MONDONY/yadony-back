package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Lot 2 (2026-08-19) — pays (et devise, qui en dérive) figés dès le premier
 * mouvement d'argent : portefeuille non vide, transaction portefeuille passée,
 * envoi engagé, ou compte Stripe Connect déjà créé.
 */
@ExtendWith(MockitoExtension.class)
class CountryLockServiceTest {

    @Mock UserRepository userRepository;
    @Mock WalletAccountRepository walletAccountRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock BidRepository bidRepository;
    @InjectMocks CountryLockService service;

    private final UUID userId = UUID.randomUUID();

    private void stubNothingLocking() {
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.empty());
        lenient().when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of());
        lenient().when(walletTransactionRepository.existsByUserId(userId)).thenReturn(false);
        lenient().when(bidRepository.existsBySenderIdAndStatusIn(eq(userId), anyList())).thenReturn(false);
    }

    @Test
    void isLocked_noWalletNoTransactionNoBidNoStripeAccount_returnsFalse() {
        stubNothingLocking();

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    @DisplayName("Un compte Stripe Connect existant verrouille le pays")
    void stripeAccountLocksCountry() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setStripeAccountId("acct_123");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        // Court-circuité par hasStripeAccount() : conservés en lenient pour rester
        // fidèles au scénario même si isLocked() ne les consulte jamais ici.
        lenient().when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of());
        lenient().when(walletTransactionRepository.existsByUserId(userId)).thenReturn(false);
        lenient().when(bidRepository.existsBySenderIdAndStatusIn(eq(userId), anyList()))
                .thenReturn(false);

        assertThat(service.isLocked(userId)).isTrue();
    }

    @Test
    void isLocked_userWithBlankStripeAccountId_doesNotLockOnItsOwn() {
        stubNothingLocking();
        UserEntity user = new UserEntity();
        user.setStripeAccountId("");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

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
