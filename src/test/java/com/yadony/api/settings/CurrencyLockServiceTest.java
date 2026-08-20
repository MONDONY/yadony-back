package com.yadony.api.settings;

import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tâche 3 (2026-08-20) — le verrou devise ne porte plus que sur le solde réellement
 * détenu : ni l'historique de transactions, ni les envois engagés, ni le compte
 * Stripe Connect ne le figent. Un solde retombé à zéro rouvre le choix de devise :
 * c'est le comportement voulu, pas un oubli.
 */
@ExtendWith(MockitoExtension.class)
class CurrencyLockServiceTest {

    @Mock WalletAccountRepository walletAccountRepository;
    @InjectMocks CurrencyLockService service;

    private final UUID userId = UUID.randomUUID();

    private WalletAccountEntity account(String currency, BigDecimal balance) {
        WalletAccountEntity account = new WalletAccountEntity();
        account.setUserId(userId);
        account.setCurrency(currency);
        account.setBalance(balance);
        return account;
    }

    @Test
    @DisplayName("Solde nul dans toutes les devises : pas de verrou")
    void isLocked_zeroBalanceInEveryCurrency_returnsFalse() {
        when(walletAccountRepository.findAllByUserId(userId))
                .thenReturn(List.of(account("EUR", BigDecimal.ZERO), account("XOF", BigDecimal.ZERO)));

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    @DisplayName("Solde non nul dans une seule devise : verrouille")
    void isLocked_nonZeroBalanceInOneCurrency_returnsTrue() {
        when(walletAccountRepository.findAllByUserId(userId))
                .thenReturn(List.of(account("EUR", BigDecimal.ZERO), account("XOF", new BigDecimal("500"))));

        assertThat(service.isLocked(userId)).isTrue();
    }

    @Test
    @DisplayName("Transaction passée mais solde revenu à zéro : pas de verrou (changement de fond)")
    void isLocked_pastTransactionButBalanceBackToZero_returnsFalse() {
        // Le solde reflète déjà l'historique des transactions passées : une fois
        // qu'un solde positif a été entièrement dépensé/retiré, il retombe à zéro et
        // ne figure plus dans le résultat de findAllByUserId comme "verrouillant".
        // Rien dans CurrencyLockService ne consulte plus l'historique de transactions.
        when(walletAccountRepository.findAllByUserId(userId))
                .thenReturn(List.of(account("EUR", BigDecimal.ZERO)));

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    @DisplayName("Aucun compte portefeuille du tout : pas de verrou")
    void isLocked_noWalletAccountAtAll_returnsFalse() {
        when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of());

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    @DisplayName("Un compte Connect seul, sans solde, ne verrouille pas la devise")
    void isLocked_connectAccountAloneWithoutBalance_returnsFalse() {
        // CurrencyLockService n'a aucune dépendance vers UserRepository : un compte
        // Stripe Connect existant est structurellement hors de portée de ce verrou.
        when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of());

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    @DisplayName("Un envoi engagé seul, sans solde, ne verrouille pas la devise")
    void isLocked_committedBidAloneWithoutBalance_returnsFalse() {
        // CurrencyLockService n'a aucune dépendance vers BidRepository : un envoi
        // engagé (payment escrowed, accepted, ...) est structurellement hors de
        // portée de ce verrou, seul l'argent réellement détenu le fige.
        when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of());

        assertThat(service.isLocked(userId)).isFalse();
    }
}
