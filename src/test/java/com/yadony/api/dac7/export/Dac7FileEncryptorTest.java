package com.yadony.api.dac7.export;

import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Fichier DAC7 : gzip puis OpenPGP pour la sous-clé de chiffrement de la DGFiP")
class Dac7FileEncryptorTest {

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("clé GnuPG habituelle (primaire SC + sous-clé E) : chiffré pour la SOUS-CLÉ, aller-retour, intégrité, AES-256")
    void sousCleDeChiffrement(@TempDir Path dir) throws Exception {
        Dac7TestPgpKeys.Ring ring = Dac7TestPgpKeys.standard();
        Path keyFile = Dac7TestPgpKeys.write(dir, ring);
        byte[] xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><a>é</a>".getBytes(StandardCharsets.UTF_8);

        PGPPublicKey key = Dac7FileEncryptor.readEncryptionKey(keyFile, NOW);
        byte[] gpg = Dac7FileEncryptor.gzipAndEncrypt(xml, key,
                "DPIDAC7_2026_123456789_000_20270115100000.xml.gz");

        assertThat(key.getKeyID()).isEqualTo(ring.subkeys().get(0).getKeyID()).isNotEqualTo(ring.master().getKeyID());
        assertThat(Dac7TestPgpKeys.recipientKeyId(gpg)).isEqualTo(ring.subkeys().get(0).getKeyID());
        assertThat(new String(gpg, StandardCharsets.ISO_8859_1)).doesNotContain("<a>");
        Dac7TestPgpKeys.Decrypted clear = Dac7TestPgpKeys.decrypt(gpg, ring.subkeys().get(0));
        assertThat(clear.xml()).isEqualTo(xml);
        assertThat(clear.literalName()).isEqualTo("DPIDAC7_2026_123456789_000_20270115100000.xml.gz");
        assertThat(clear.integrityChecked()).isTrue();
        assertThat(clear.symmetricAlgorithm()).isEqualTo(SymmetricKeyAlgorithmTags.AES_256);
        assertThat(new String(gpg, 0, Math.min(gpg.length, 40), StandardCharsets.ISO_8859_1))
                .doesNotContain("BEGIN PGP");
    }

    @Test
    @DisplayName("sous-clé expirée ou révoquée ignorée : la sous-clé valide suivante est choisie")
    void expireeEtRevoqueeIgnorees(@TempDir Path dir) throws Exception {
        Dac7TestPgpKeys.Ring ring = Dac7TestPgpKeys.ring(false, new Dac7TestPgpKeys.Sub(true, true, false),
                new Dac7TestPgpKeys.Sub(true, false, true), Dac7TestPgpKeys.Sub.encryption());
        PGPPublicKey key = Dac7FileEncryptor.readEncryptionKey(Dac7TestPgpKeys.write(dir, ring), NOW);
        assertThat(key.getKeyID()).isEqualTo(ring.subkeys().get(2).getKeyID());
    }

    @Test
    @DisplayName("seulement des sous-clés expirées ou révoquées : erreur claire, sans contenu de clé")
    void aucuneSousCleValide(@TempDir Path dir) throws Exception {
        Dac7TestPgpKeys.Ring ring = Dac7TestPgpKeys.ring(false, new Dac7TestPgpKeys.Sub(true, true, false),
                new Dac7TestPgpKeys.Sub(true, false, true));
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(Dac7TestPgpKeys.write(dir, ring), NOW))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class)
                .hasMessageContaining("chiffrement")
                .hasMessageNotContaining("BEGIN");
    }

    @Test
    @DisplayName("primaire de signature sans sous-clé de chiffrement : refusée ; primaire marquée chiffrement : acceptée")
    void primaire(@TempDir Path dir) throws Exception {
        Dac7TestPgpKeys.Ring signOnly = Dac7TestPgpKeys.ring(false, new Dac7TestPgpKeys.Sub(false, false, false));
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(Dac7TestPgpKeys.write(dir, signOnly), NOW))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class);

        Dac7TestPgpKeys.Ring encryptingMaster = Dac7TestPgpKeys.ring(true);
        PGPPublicKey key = Dac7FileEncryptor.readEncryptionKey(Dac7TestPgpKeys.write(dir, encryptingMaster), NOW);
        assertThat(key.getKeyID()).isEqualTo(encryptingMaster.master().getKeyID());
        byte[] gpg = Dac7FileEncryptor.gzipAndEncrypt(new byte[]{1, 2, 3}, key, "x.xml.gz");
        assertThat(Dac7TestPgpKeys.decrypt(gpg, encryptingMaster.master()).xml()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("clé absente, illisible : erreur sans contenu")
    void cleIndisponible(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(dir.resolve("absente.asc"), NOW))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class);
        Path junk = Files.writeString(dir.resolve("junk.asc"), "pas une clé");
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(junk, NOW))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class)
                .hasMessageNotContaining("pas une clé");
        assertThatThrownBy(() -> Dac7FileEncryptor.readEncryptionKey(null, NOW))
                .isInstanceOf(Dac7FileEncryptor.KeyUnavailableException.class);
    }
}
