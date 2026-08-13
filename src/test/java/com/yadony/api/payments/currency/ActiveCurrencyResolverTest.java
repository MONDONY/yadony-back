package com.yadony.api.payments.currency;

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
 * par ce composant plutôt que de refaire la recherche en préférences utilisateur.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActiveCurrencyResolver — devise de travail de l'utilisateur")
class ActiveCurrencyResolverTest {

    @Mock
    UserBusinessPrefsRepository repository;

    ActiveCurrencyResolver resolver() {
        return new ActiveCurrencyResolver(repository);
    }

    @Test
    void returnsTheCurrencyStoredInUserPreferences() {
        UUID userId = UUID.randomUUID();
        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setCurrencyCode("XOF");
        when(repository.findById(userId)).thenReturn(Optional.of(prefs));

        assertThat(resolver().resolve(userId)).isEqualTo("XOF");
    }

    @Test
    @DisplayName("un utilisateur sans préférences retombe sur la devise par défaut")
    void fallsBackToDefaultWhenNoPreferencesExist() {
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.empty());

        assertThat(resolver().resolve(userId)).isEqualTo(ActiveCurrencyResolver.DEFAULT_CURRENCY);
    }

    @Test
    @DisplayName("un identifiant nul ne déclenche aucune requête")
    void nullUserIdShortCircuitsTheLookup() {
        // Les chemins de recherche appellent le résolveur sans visiteur authentifié :
        // interroger la base avec un identifiant nul lèverait une exception.
        assertThat(resolver().resolve(null)).isEqualTo(ActiveCurrencyResolver.DEFAULT_CURRENCY);
        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theDefaultCurrencyIsStoredInUppercase() {
        // Les contraintes CHECK des tables métier n'acceptent que des majuscules :
        // un défaut en minuscules aurait été rejeté à l'insertion.
        assertThat(ActiveCurrencyResolver.DEFAULT_CURRENCY)
                .isEqualTo(ActiveCurrencyResolver.DEFAULT_CURRENCY.toUpperCase());
    }
}
