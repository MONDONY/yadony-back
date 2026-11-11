package com.yadony.api.dac7;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Export DAC7 (lot 3b). Détecté par {@code @ConfigurationPropertiesScan}. Aucune valeur n'est posée par défaut sauf
 * le nom commercial : tant que le SIREN, la raison sociale et la ville du siège manquent, l'export refuse de partir
 * ({@code OPERATOR_CONFIG_MISSING}).
 *
 * @param publicKeyPath chemin du fichier de clé publique OpenPGP de la DGFiP (test ou production), JAMAIS commité ;
 *                      absent : le XML part en clair, {@code X-Dac7-Encrypted: false}
 * @param declarantSiren SIREN (ou IDSP) de l'opérateur, 9 caractères : nom du fichier, TIN et IN de l'opérateur
 * @param allowUnencrypted autoriser l'export en clair sans clé (faux par défaut : 409 {@code dac7-unencrypted-not-allowed})
 */
@ConfigurationProperties(prefix = "yadony.dac7.export")
public record Dac7ExportProperties(String publicKeyPath, String declarantSiren, String operatorName,
                                   String operatorBusinessName, String operatorVat, String operatorStreet,
                                   String operatorPostCode, String operatorCity, Boolean allowUnencrypted) {

    @ConstructorBinding
    public Dac7ExportProperties {
    }

    /** Compatibilité des appels sans l'option de clair (refusé). */
    public Dac7ExportProperties(String publicKeyPath, String declarantSiren, String operatorName,
                                String operatorBusinessName, String operatorVat, String operatorStreet,
                                String operatorPostCode, String operatorCity) {
        this(publicKeyPath, declarantSiren, operatorName, operatorBusinessName, operatorVat, operatorStreet,
                operatorPostCode, operatorCity, null);
    }

    /** Export en clair autorisé seulement sur demande explicite ({@code allow-unencrypted: true}, essais). */
    public boolean unencryptedAllowed() {
        return Boolean.TRUE.equals(allowUnencrypted);
    }

    static final String DEFAULT_BUSINESS_NAME = "Yadony";

    public boolean operatorConfigured() {
        return declarantSiren != null && declarantSiren.strip().matches("[A-Za-z0-9]{9}")
                && notBlank(operatorName) && notBlank(operatorCity);
    }

    public String businessNameOrDefault() {
        return notBlank(operatorBusinessName) ? operatorBusinessName.strip() : DEFAULT_BUSINESS_NAME;
    }

    public boolean encryptionConfigured() {
        return notBlank(publicKeyPath);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
