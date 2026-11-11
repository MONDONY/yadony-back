package com.yadony.api.dac7.export;

import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Iterator;
import java.util.zip.GZIPOutputStream;

/**
 * Préparation du fichier pour le dépôt Télé-TD (cahier des charges § 5.1.6.3 et guide de chiffrement de la DGFiP) :
 * compression gzip, PUIS chiffrement OpenPGP avec la clé publique de la DGFiP, comme {@code gpg -e -r <clé>} : sortie
 * binaire (pas d'armure ASCII), sans signature, paquet d'intégrité (MDC) et AES-256.
 *
 * <p>La clé publique (fichier {@code .asc} ou binaire de la DGFiP, test ou production selon la nature du fichier) est
 * lue à chaque export depuis {@code yadony.dac7.export.public-key-path}. Elle n'est jamais commitée.
 */
public final class Dac7FileEncryptor {

    private Dac7FileEncryptor() {
    }

    /** Clé de chiffrement illisible ou absente du fichier : message sans contenu de clé. */
    public static final class KeyUnavailableException extends RuntimeException {
        KeyUnavailableException(String message) {
            super(message);
        }
    }

    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("Compression gzip impossible");
        }
        return out.toByteArray();
    }

    /**
     * @param literalName nom porté par le paquet de données, celui du fichier compressé ({@code ….xml.gz})
     */
    public static byte[] gzipAndEncrypt(byte[] xml, PGPPublicKey key, String literalName) {
        byte[] compressed = gzip(xml);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            SecureRandom random = new SecureRandom();
            PGPEncryptedDataGenerator generator = new PGPEncryptedDataGenerator(
                    new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                            .setWithIntegrityPacket(true).setSecureRandom(random));
            generator.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(key).setSecureRandom(random));
            try (OutputStream encrypted = generator.open(out, new byte[1 << 16])) {
                PGPLiteralDataGenerator literal = new PGPLiteralDataGenerator();
                try (OutputStream data = literal.open(encrypted, PGPLiteralData.BINARY, literalName,
                        compressed.length, new Date())) {
                    data.write(compressed);
                }
            }
        } catch (IOException | PGPException e) {
            throw new IllegalStateException("Chiffrement OpenPGP impossible : " + e.getClass().getSimpleName());
        }
        return out.toByteArray();
    }

    /** Première clé de chiffrement valide du fichier (armure ASCII ou binaire). */
    public static PGPPublicKey readEncryptionKey(Path path) {
        if (path == null || !Files.isReadable(path)) {
            throw new KeyUnavailableException("Clé publique DAC7 introuvable ou illisible");
        }
        try (InputStream raw = Files.newInputStream(path); InputStream in = PGPUtil.getDecoderStream(raw)) {
            PGPPublicKeyRingCollection rings = new PGPPublicKeyRingCollection(in, new BcKeyFingerprintCalculator());
            for (Iterator<PGPPublicKeyRing> it = rings.getKeyRings(); it.hasNext(); ) {
                for (Iterator<PGPPublicKey> keys = it.next().getPublicKeys(); keys.hasNext(); ) {
                    PGPPublicKey key = keys.next();
                    if (key.isEncryptionKey() && !key.hasRevocation()) {
                        return key;
                    }
                }
            }
        } catch (IOException | PGPException | RuntimeException e) {
            throw new KeyUnavailableException("Clé publique DAC7 illisible : " + e.getClass().getSimpleName());
        }
        throw new KeyUnavailableException("Aucune clé de chiffrement dans le fichier de clé publique DAC7");
    }
}
