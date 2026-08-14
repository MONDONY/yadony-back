package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repli {@code @PrePersist} du username.
 *
 * <p>Le suffixe tenait sur 4 chiffres : 10 000 valeurs par seconde, ce qui paraît large mais
 * ne l'est pas — le paradoxe des anniversaires donne ~12 % de collision dès 50 insertions dans
 * la même seconde. Une suite d'intégration en crée davantage, et la CI a fini par tomber
 * dessus : {@code Unique index violation: USERS(USERNAME)}, build rouge et déploiement staging
 * bloqué, sans qu'aucun code métier soit en cause.
 */
class UserEntityUsernameFallbackTest {

    private static String generatedUsername() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.invokeMethod(user, "ensureUsername");
        return user.getUsername();
    }

    @Test
    void fallback_neDepassePasLaLongueurDeColonne() {
        // La colonne fait 32 caractères : un username plus long serait tronqué ou rejeté
        // à l'insertion, exactement le genre d'erreur opaque que ce repli doit éviter.
        assertThat(generatedUsername()).hasSizeLessThanOrEqualTo(32);
    }

    @Test
    void fallback_conserveLePrefixeEtLHorodatage() {
        assertThat(generatedUsername()).matches("user\\d{10}[0-9a-f]{12}");
    }

    /**
     * Reproduit la rafale qui cassait la CI : beaucoup d'insertions dans la même seconde,
     * donc un horodatage identique pour toutes. Seule l'entropie du suffixe les sépare.
     */
    @Test
    void fallback_millesGenerationsDansLaMemeSeconde_restentUniques() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            assertThat(seen.add(generatedUsername()))
                    .as("collision de username à la génération %d", i)
                    .isTrue();
        }
    }

    @Test
    void fallback_neRemplacePasUnUsernameDejaFourni() {
        UserEntity user = new UserEntity();
        user.setUsername("choisi-par-appelant");
        ReflectionTestUtils.invokeMethod(user, "ensureUsername");
        assertThat(user.getUsername()).isEqualTo("choisi-par-appelant");
    }
}
