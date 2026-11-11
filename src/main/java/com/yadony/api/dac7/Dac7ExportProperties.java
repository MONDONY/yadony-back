package com.yadony.api.dac7;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Export DAC7 (lot 3b). Détecté par {@code @ConfigurationPropertiesScan}. Aucune valeur n'est posée par défaut sauf
 * le nom commercial : tant que le SIREN, la raison sociale et la ville du siège manquent, l'export refuse de partir
 * ({@code OPERATOR_CONFIG_MISSING}).
 *
 * @param publicKeyPath chemin du fichier de clé publique OpenPGP de la DGFiP (test ou production), JAMAIS commité ;
 *                      absent : le XML part en clair, {@code X-Dac7-Encrypted: false}
 * @param declarantSiren SIREN (ou IDSP) de l'opérateur, 9 caractères : nom du fichier, TIN et IN de l'opérateur
 */
@ConfigurationProperties(prefix = "yadony.dac7.export")
public record Dac7ExportProperties(String publicKeyPath, String declarantSiren, String operatorName,
                                   String operatorBusinessName, String operatorVat, String operatorStreet,
                                   String operatorPostCode, String operatorCity) {

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
