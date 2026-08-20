package com.yadony.api.payments.currency;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La politique de repli doit rester à un seul endroit : chaque paquet métier passe
 * par ce composant plutôt que de refaire la derivation pays -> devise.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActiveCurrencyResolver — devise de travail de l'utilisateur")
class ActiveCurrencyResolverTest {

    @Mock
    UserRepository userRepository;

    @Mock
    UserBusinessPrefsRepository userBusinessPrefsRepository;

    ActiveCurrencyResolver resolver() {
        return new ActiveCurrencyResolver(userRepository, userBusinessPrefsRepository);
    }

    private UserBusinessPrefsEntity prefsWithCurrency(UUID userId, String currencyCode) {
        UserBusinessPrefsEntity e = new UserBusinessPrefsEntity();
        e.setUserId(userId);
        e.setCurrencyCode(currencyCode);
        return e;
    }

    @Test
    @DisplayName("Une ligne de portefeuille existante fait foi, meme si le pays a change depuis")
    void resolvesFromWalletCurrencyWhenPrefsRowExists() {
        UUID userId = UUID.randomUUID();
        when(userBusinessPrefsRepository.findById(userId))
                .thenReturn(Optional.of(prefsWithCurrency(userId, "XOF")));

        assertThat(resolver().resolve(userId)).isEqualTo("XOF");
        verify(userRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Sans ligne de portefeuille, le pays sert de valeur initiale (inscription)")
    void derivesInitialCurrencyFromCountryWhenNoWalletRowExists() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setCountry("CA");
        when(userBusinessPrefsRepository.findById(userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(resolver().resolve(userId)).isEqualTo("CAD");
    }

    @Test
    @DisplayName("Un pays absent ou hors catalogue retombe sur l'euro, sans ligne de portefeuille")
    void fallsBackToEurWhenCountryUnknown() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setCountry(null);
        when(userBusinessPrefsRepository.findById(userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(resolver().resolve(userId)).isEqualTo("EUR");
        assertThat(resolver().resolve(null)).isEqualTo("EUR");
    }

    @Test
    @DisplayName("un identifiant nul ne déclenche aucune requête")
    void nullUserIdShortCircuitsTheLookup() {
        // Les chemins de recherche appellent le résolveur sans visiteur authentifié :
        // interroger la base avec un identifiant nul lèverait une exception.
        assertThat(resolver().resolve(null)).isEqualTo(ActiveCurrencyResolver.DEFAULT_CURRENCY);
        verify(userBusinessPrefsRepository, never()).findById(org.mockito.ArgumentMatchers.any());
        verify(userRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theDefaultCurrencyIsStoredInUppercase() {
        // Les contraintes CHECK des tables métier n'acceptent que des majuscules :
        // un défaut en minuscules aurait été rejeté à l'insertion.
        assertThat(ActiveCurrencyResolver.DEFAULT_CURRENCY)
                .isEqualTo(ActiveCurrencyResolver.DEFAULT_CURRENCY.toUpperCase());
    }

    @Test
    @DisplayName("un pays hors catalogue retombe sur l'euro, sans ligne de portefeuille")
    void unsupportedCountryFallsBackToEur() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setCountry("ZZ");
        when(userBusinessPrefsRepository.findById(userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(resolver().resolve(userId)).isEqualTo(ActiveCurrencyResolver.DEFAULT_CURRENCY);
    }

    @Test
    @DisplayName("un utilisateur introuvable retombe sur l'euro")
    void unknownUserFallsBackToEur() {
        UUID userId = UUID.randomUUID();
        when(userBusinessPrefsRepository.findById(userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(resolver().resolve(userId)).isEqualTo(ActiveCurrencyResolver.DEFAULT_CURRENCY);
    }
}
