package com.yadony.api.dac7.export;

import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Fichier DAC7 : gzip puis OpenPGP avec la clé publique")
class Dac7FileEncryptorTest {

    @Test
    @DisplayName("chiffré avec la clé publique, déchiffrable par la clé privée : gzip du XML d'origine, intégrité, AES-256")
    void aller_retour(@TempDir Path dir) throws Exception {
        Path keyFile = Dac7TestPgpKeys.writeArmoredPublicKey(dir);
        byte[] xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><a>é</a>".getBytes(StandardCharsets.UTF_8);

        byte[] gpg = Dac7FileEncryptor.gzipAndEncrypt(xml, Dac7FileEncryptor.readEncryptionKey(keyFile),
                "DPIDAC7_2026_123456789_000_20270115100000.xml.gz");

        assertThat(new String(gpg, StandardCharsets.ISO_8859_1)).doesNotContain("<a>");
        Dac7TestPgpKeys.Decrypted clear = Dac7TestPgpKeys.decrypt(gpg);
        assertThat(clear.xml()).isEqualTo(xml);
        assertThat(clear.literalName()).isEqualTo("DPIDAC7_2026_123456789_000_20270115100000.xml.gz");
        assertThat(clear.integrityChecked()).isTrue();
        assertThat(clear.symmetricAlgorithm()).isEqualTo(SymmetricKeyAlgorithmTags.AES_256);
        // Binaire, pas d'armure ASCII (comme « gpg -e »).
        assertThat(new String(gpg, 0, Math.min(gpg.length, 40), StandardCharsets.ISO_8859_1))
                .doesNotContain("BEGIN PGP");
    }

    @Test
    @DisplayName("clé absente, illisible ou sans clé de chiffrement : erreur sans contenu")
    void cleIndisponible(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(dir.resolve("absente.asc")))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class);
        Path junk = Files.writeString(dir.resolve("junk.asc"), "pas une clé");
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(junk))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class)
                .hasMessageNotContaining("pas une clé");
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(null))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class);
    }
}
