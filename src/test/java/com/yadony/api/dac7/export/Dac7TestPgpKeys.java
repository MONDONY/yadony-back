package com.yadony.api.dac7.export;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Trousseaux OpenPGP de TEST générés en mémoire (jamais la clé de la DGFiP), de la forme GnuPG habituelle : clé
 * primaire de certification et de signature, sous-clé(s) de chiffrement liées par une signature qui porte leurs
 * drapeaux. Les clés privées restent en mémoire pour déchiffrer le fichier produit.
 */
public final class Dac7TestPgpKeys {

    /** Une sous-clé à ajouter : drapeau de chiffrement, expirée depuis 9 jours, révoquée. */
    public record Sub(boolean encrypts, boolean expired, boolean revoked) {
        public static Sub encryption() {
            return new Sub(true, false, false);
        }
    }

    /** Trousseau public et paires de clés (primaire, puis sous-clés dans l'ordre). */
    public record Ring(PGPPublicKeyRing publicRing, BcPGPKeyPair master, List<BcPGPKeyPair> subkeys) {
    }

    private static Ring standard;

    private Dac7TestPgpKeys() {
    }

    /** Forme habituelle : primaire « SC » sans chiffrement + une sous-clé « E » valide. */
    public static synchronized Ring standard() throws Exception {
        if (standard == null) {
            standard = ring(false, Sub.encryption());
        }
        return standard;
    }

    public static Ring ring(boolean masterEncrypts, Sub... subs) throws Exception {
        Date now = new Date();
        BcPGPKeyPair master = new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, rsa(), now);
        PGPSignatureSubpacketGenerator masterFlags = new PGPSignatureSubpacketGenerator();
        masterFlags.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA
                | (masterEncrypts ? KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE : 0));
        PGPKeyRingGenerator generator = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, master,
                "DGFiP test <test@example.invalid>", new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
                masterFlags.generate(), null, signer(), null);
        List<BcPGPKeyPair> pairs = new ArrayList<>();
        for (Sub sub : subs) {
            Date created = sub.expired() ? new Date(now.getTime() - 10L * 86_400_000L) : now;
            BcPGPKeyPair pair = new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, rsa(), created);
            PGPSignatureSubpacketGenerator flags = new PGPSignatureSubpacketGenerator();
            flags.setKeyFlags(false, sub.encrypts() ? KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE
                    : KeyFlags.SIGN_DATA);
            if (sub.expired()) {
                flags.setKeyExpirationTime(false, 86_400L);
            }
            generator.addSubKey(pair, flags.generate(), null);
            pairs.add(pair);
        }
        PGPPublicKeyRing ring = generator.generatePublicKeyRing();
        for (int i = 0; i < subs.length; i++) {
            if (subs[i].revoked()) {
                PGPPublicKey sub = ring.getPublicKey(pairs.get(i).getKeyID());
                PGPSignatureGenerator revocation = new PGPSignatureGenerator(signer(), master.getPublicKey());
                revocation.init(PGPSignature.SUBKEY_REVOCATION, master.getPrivateKey());
                PGPSignature signature = revocation.generateCertification(ring.getPublicKey(), sub);
                ring = PGPPublicKeyRing.insertPublicKey(ring, PGPPublicKey.addCertification(sub, signature));
            }
        }
        return new Ring(ring, master, pairs);
    }

    /** Écrit le trousseau en armure ASCII, comme le fichier .asc de la DGFiP. */
    public static Path write(Path dir, Ring ring) throws Exception {
        Path file = Files.createTempFile(dir, "dgfip-test", ".asc");
        try (OutputStream out = new ArmoredOutputStream(Files.newOutputStream(file))) {
            ring.publicRing().encode(out);
        }
        return file;
    }

    public static Path writeArmoredPublicKey(Path dir) throws Exception {
        return write(dir, standard());
    }

    /** Identifiant de la clé destinataire du paquet chiffré. */
    public static long recipientKeyId(byte[] gpg) throws Exception {
        return encryptedData(gpg).getKeyID();
    }

    /** Déchiffre (avec la sous-clé de chiffrement du trousseau standard) puis décompresse. */
    public static Decrypted decrypt(byte[] gpg) throws Exception {
        return decrypt(gpg, standard().subkeys().get(0));
    }

    public static Decrypted decrypt(byte[] gpg, BcPGPKeyPair key) throws Exception {
        PGPPublicKeyEncryptedData data = encryptedData(gpg);
        BcPublicKeyDataDecryptorFactory factory = new BcPublicKeyDataDecryptorFactory(key.getPrivateKey());
        int algorithm = data.getSymmetricAlgorithm(factory);
        InputStream clear = data.getDataStream(factory);
        PGPLiteralData literal = (PGPLiteralData) new BcPGPObjectFactory(clear).nextObject();
        byte[] gz = literal.getInputStream().readAllBytes();
        boolean verified = data.isIntegrityProtected() && data.verify();
        byte[] xml;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            xml = in.readAllBytes();
        }
        return new Decrypted(xml, literal.getFileName(), verified, algorithm);
    }

    public record Decrypted(byte[] xml, String literalName, boolean integrityChecked, int symmetricAlgorithm) {
    }

    private static PGPPublicKeyEncryptedData encryptedData(byte[] gpg) throws Exception {
        BcPGPObjectFactory factory = new BcPGPObjectFactory(PGPUtil.getDecoderStream(new ByteArrayInputStream(gpg)));
        Object first = factory.nextObject();
        PGPEncryptedDataList list = first instanceof PGPEncryptedDataList l ? l
                : (PGPEncryptedDataList) factory.nextObject();
        return (PGPPublicKeyEncryptedData) list.get(0);
    }

    private static BcPGPContentSignerBuilder signer() {
        return new BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256);
    }

    private static org.bouncycastle.crypto.AsymmetricCipherKeyPair rsa() {
        RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
        generator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 12));
        return generator.generateKeyPair();
    }
}
