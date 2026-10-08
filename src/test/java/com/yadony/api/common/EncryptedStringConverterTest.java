package com.yadony.api.common;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EncryptedStringConverter — chiffrement de colonne au repos")
class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    private static EncryptionService previous;

    @BeforeAll
    static void initEncryption() {
        // Le holder statique est partagé avec les contextes Spring déjà en cache dans
        // ce fork : on garde l'instance en place pour la remettre après, sinon les
        // classes suivantes déchiffreraient leurs lignes H2 avec une autre clé.
        try {
            previous = EncryptionSupport.encryption();
        } catch (IllegalStateException notYetInitialized) {
            previous = null;
        }
        // Même passphrase que application-test.yml, par prudence si la restauration
        // n'avait pas lieu (fork interrompu).
        new EncryptionSupport(new EncryptionService(
                "yadony-test-encryption-key-not-for-production-use-32-bytes"));
    }

    @AfterAll
    static void restoreEncryption() {
        if (previous != null) {
            new EncryptionSupport(previous);
        }
    }

    @Test
    @DisplayName("round-trip : le clair est restitué après chiffrement/déchiffrement")
    void roundTrip() {
        String plain = "+221701234567";
        String db = converter.convertToDatabaseColumn(plain);

        assertThat(db).isNotNull().isNotEqualTo(plain);       // stocké chiffré
        assertThat(converter.convertToEntityAttribute(db)).isEqualTo(plain); // lu en clair
    }

    @Test
    @DisplayName("chiffrement randomisé : deux chiffrements du même clair diffèrent (IV aléatoire, non corrélable)")
    void randomizedCiphertext() {
        String plain = "Awa Diop";
        String a = converter.convertToDatabaseColumn(plain);
        String b = converter.convertToDatabaseColumn(plain);

        assertThat(a).isNotEqualTo(b);
        assertThat(converter.convertToEntityAttribute(a)).isEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(b)).isEqualTo(plain);
    }

    @Test
    @DisplayName("null préservé dans les deux sens")
    void nullPassthrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
