package com.yadony.api.dac7.export;

import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
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

    /**
     * Clé destinataire, comme GnuPG la choisit : une SOUS-CLÉ dont la signature de liaison porte le drapeau de
     * chiffrement ({@code ENCRYPT_COMMS} ou {@code ENCRYPT_STORAGE}), ni révoquée ni expirée à {@code now} (la plus
     * récente s'il y en a plusieurs) ; la clé primaire seulement si sa propre signature porte ce drapeau. Jamais une
     * primaire de certification ou de signature. Trousseau révoqué ou expiré : ignoré. Fichier en armure ASCII ou
     * binaire. Les erreurs ne citent jamais le contenu de la clé.
     */
    public static PGPPublicKey readEncryptionKey(Path path, java.time.Instant now) {
        if (path == null || !Files.isReadable(path)) {
            throw new KeyUnavailableException("Clé publique DAC7 introuvable ou illisible");
        }
        PGPPublicKeyRingCollection rings;
        try (InputStream raw = Files.newInputStream(path); InputStream in = PGPUtil.getDecoderStream(raw)) {
            rings = new PGPPublicKeyRingCollection(in, new BcKeyFingerprintCalculator());
        } catch (IOException | PGPException | RuntimeException e) {
            throw new KeyUnavailableException("Clé publique DAC7 illisible : " + e.getClass().getSimpleName());
        }
        for (Iterator<PGPPublicKeyRing> it = rings.getKeyRings(); it.hasNext(); ) {
            PGPPublicKeyRing ring = it.next();
            PGPPublicKey master = ring.getPublicKey();
            if (!usable(master, now)) {
                continue;
            }
            PGPPublicKey chosen = null;
            for (Iterator<PGPPublicKey> keys = ring.getPublicKeys(); keys.hasNext(); ) {
                PGPPublicKey key = keys.next();
                if (key.isMasterKey() || !usable(key, now) || !key.isEncryptionKey()
                        || (flags(key) & ENCRYPTION_FLAGS) == 0) {
                    continue;
                }
                if (chosen == null || key.getCreationTime().after(chosen.getCreationTime())) {
                    chosen = key;
                }
            }
            if (chosen == null && master.isEncryptionKey() && (flags(master) & ENCRYPTION_FLAGS) != 0) {
                chosen = master;
            }
            if (chosen != null) {
                return chosen;
            }
        }
        throw new KeyUnavailableException("Aucune clé de chiffrement valide (sous-clé marquée chiffrement, non "
                + "révoquée et non expirée) dans le fichier de clé publique DAC7");
    }

    static final int ENCRYPTION_FLAGS = KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE;

    private static boolean usable(PGPPublicKey key, java.time.Instant now) {
        if (key.hasRevocation()) {
            return false;
        }
        long valid = key.getValidSeconds();
        return valid <= 0 || key.getCreationTime().toInstant().plusSeconds(valid).isAfter(now);
    }

    /** Drapeaux portés par les auto-signatures : liaison pour une sous-clé, certification de la primaire sinon. */
    private static int flags(PGPPublicKey key) {
        int flags = 0;
        Iterator<PGPSignature> signatures = key.isMasterKey() ? key.getSignatures()
                : key.getSignaturesOfType(PGPSignature.SUBKEY_BINDING);
        while (signatures.hasNext()) {
            PGPSignature signature = signatures.next();
            if (key.isMasterKey() && signature.getKeyID() != key.getKeyID()) {
                continue;
            }
            PGPSignatureSubpacketVector hashed = signature.getHashedSubPackets();
            if (hashed != null) {
                flags |= hashed.getKeyFlags();
            }
        }
        return flags;
    }
}
