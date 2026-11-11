package com.yadony.api.dac7.export;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Paire de clés OpenPGP de TEST, générée en mémoire (jamais la clé de la DGFiP) : la clé publique est écrite dans un
 * fichier temporaire, la clé privée sert à déchiffrer le fichier produit.
 */
public final class Dac7TestPgpKeys {

    private static BcPGPKeyPair pair;

    private Dac7TestPgpKeys() {
    }

    public static synchronized BcPGPKeyPair pair() throws Exception {
        if (pair == null) {
            RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
            generator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 12));
            AsymmetricCipherKeyPair keys = generator.generateKeyPair();
            pair = new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, keys, new Date());
        }
        return pair;
    }

    /** Écrit la clé publique en armure ASCII, comme le fichier .asc de la DGFiP. */
    public static Path writeArmoredPublicKey(Path dir) throws Exception {
        Path file = dir.resolve("dgfip-test.asc");
        PGPPublicKeyRing ring = new PGPPublicKeyRing(List.of(pair().getPublicKey()));
        try (OutputStream out = new ArmoredOutputStream(Files.newOutputStream(file))) {
            ring.encode(out);
        }
        return file;
    }

    /** Déchiffre puis décompresse : rend le XML d'origine et le nom du paquet de données. */
    public static Decrypted decrypt(byte[] gpg) throws Exception {
        BcPGPObjectFactory factory = new BcPGPObjectFactory(PGPUtil.getDecoderStream(new ByteArrayInputStream(gpg)));
        Object first = factory.nextObject();
        PGPEncryptedDataList list = first instanceof PGPEncryptedDataList l ? l : (PGPEncryptedDataList) factory.nextObject();
        PGPPublicKeyEncryptedData data = (PGPPublicKeyEncryptedData) list.get(0);
        boolean integrity = data.isIntegrityProtected();
        InputStream clear = data.getDataStream(new BcPublicKeyDataDecryptorFactory(pair().getPrivateKey()));
        PGPLiteralData literal = (PGPLiteralData) new BcPGPObjectFactory(clear).nextObject();
        byte[] gz = literal.getInputStream().readAllBytes();
        boolean verified = !integrity || data.verify();
        byte[] xml;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            xml = in.readAllBytes();
        }
        return new Decrypted(xml, literal.getFileName(), integrity && verified,
                data.getSymmetricAlgorithm(new BcPublicKeyDataDecryptorFactory(pair().getPrivateKey())));
    }

    public record Decrypted(byte[] xml, String literalName, boolean integrityChecked, int symmetricAlgorithm) {
    }

    static int aes256() {
        return PGPEncryptedData.AES_256;
    }
}
