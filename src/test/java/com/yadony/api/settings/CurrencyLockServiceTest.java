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
 *
 * Note de couverture : {@link CurrencyLockService} n'a qu'une seule dépendance,
 * {@code WalletAccountRepository}. Les scénarios "transaction passée", "compte
 * Connect seul" et "envoi engagé seul" ne peuvent donc pas être distingués par un
 * mock différent de "aucun solde positif" — ils sont prouvés **par construction**
 * (absence structurelle de toute dépendance vers l'historique de transactions, vers
 * `UserRepository` ou vers `BidRepository`), pas par un comportement de mock
 * observable qui les différencierait entre eux.
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
        // NB : ce test exerce le même chemin de mock que
        // isLocked_zeroBalanceInEveryCurrency_returnsFalse ci-dessus — c'est attendu.
        // CurrencyLockService n'a aucune dépendance vers l'historique de transactions,
        // donc rien ne peut jamais faire réapparaître une transaction passée dans son
        // calcul : la garantie "solde à zéro efface l'historique" est prouvée par
        // construction (absence structurelle de la dépendance), pas par un mock qui
        // distinguerait ce scénario d'un simple solde nul.
        when(walletAccountRepository.findAllByUserId(userId))
                .thenReturn(List.of(account("EUR", BigDecimal.ZERO)));

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    @DisplayName("Aucun solde : compte portefeuille absent, compte Connect seul, ou envoi engagé seul, ne verrouillent pas la devise")
    void isLocked_noPositiveBalance_returnsFalseRegardlessOfOtherState() {
        // Un seul stub couvre trois scénarios métier distincts, tous prouvés par
        // construction plutôt que par un mock qui les distinguerait entre eux, car
        // CurrencyLockService n'a qu'une seule dépendance (WalletAccountRepository) :
        //  - aucun compte portefeuille du tout ;
        //  - un compte Stripe Connect existant mais sans solde (pas de dépendance
        //    vers UserRepository, donc structurellement hors de portée) ;
        //  - un envoi engagé (payment escrowed, accepted, ...) sans solde (pas de
        //    dépendance vers BidRepository, donc structurellement hors de portée).
        when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of());

        assertThat(service.isLocked(userId)).isFalse();
    }
}
