package com.yadony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserEntity.publicDisplayName")
class UserEntityDisplayNameTest {

    private UserEntity user(String first, String last) {
        UserEntity u = new UserEntity();
        u.setUsername("user1785153600");
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    @Test
    @DisplayName("prénom + nom → « Prénom N. »")
    void firstAndLast_abbreviatesLastName() {
        assertThat(user("Amara", "Diallo").publicDisplayName()).isEqualTo("Amara D.");
    }

    @Test
    @DisplayName("prénom seul → prénom")
    void firstOnly_returnsFirstName() {
        assertThat(user("Amara", null).publicDisplayName()).isEqualTo("Amara");
        assertThat(user("Amara", "   ").publicDisplayName()).isEqualTo("Amara");
    }

    /**
     * Cœur de la feature : c'est l'état d'un compte fraîchement créé, {@code AuthService.createUser}
     * n'écrivant ni prénom ni nom. Avant le username, ce cas produisait le numéro de téléphone
     * côté client et « Expéditeur » / « Voyageur » / {@code null} selon le service côté serveur.
     */
    @Test
    @DisplayName("aucun prénom → username")
    void noFirstName_fallsBackToUsername() {
        assertThat(user(null, null).publicDisplayName()).isEqualTo("user1785153600");
        assertThat(user("  ", null).publicDisplayName()).isEqualTo("user1785153600");
    }

    /**
     * Un compte sans prénom mais avec un nom de famille retombe sur le username plutôt que
     * d'exposer le seul patronyme : « Diallo » seul se lit comme une identité civile alors que
     * l'intéressé n'a rempli aucun champ d'affichage.
     */
    @Test
    @DisplayName("nom de famille seul → username, pas le patronyme")
    void lastNameOnly_fallsBackToUsername() {
        assertThat(user(null, "Diallo").publicDisplayName()).isEqualTo("user1785153600");
    }

    @Test
    @DisplayName("espaces autour du prénom et du nom → retirés (jamais d'initiale vide)")
    void surroundingSpaces_areTrimmed() {
        assertThat(user("  Seydou ", " Sow").publicDisplayName()).isEqualTo("Seydou S.");
        assertThat(user(" Awa ", null).publicDisplayName()).isEqualTo("Awa");
    }

    @Test
    @DisplayName("ne rend jamais null")
    void neverReturnsNull() {
        assertThat(user(null, null).publicDisplayName()).isNotNull();
        assertThat(user("Amara", "Diallo").publicDisplayName()).isNotNull();
    }
}
