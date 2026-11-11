package com.yadony.api.dac7.export;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Nom du fichier imposé par la DGFiP (« Règle de nommage d'un fichier DAC7-DPI », version 2027, revenus 2026) :
 * {@code DPIDAC7_<millésime>_<SIREN ou IDSP>_<ordre sur 3 chiffres>_<AAAAMMJJHHmmss>.xml}, puis {@code .gz} après
 * compression et {@code .gpg} après chiffrement. Seule classe à changer si la règle évolue.
 */
public final class Dac7FileNaming {

    static final String FIXED_VALUE = "DPIDAC7";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Dac7FileNaming() {
    }

    /**
     * @param declarant SIREN (ou IDSP) de l'opérateur, 9 caractères alphanumériques
     * @param sequence  numéro d'ordre de la déclaration pour ce millésime (0 pour la première, 1 pour la deuxième…)
     * @param createdAt date et heure de constitution du fichier (heure de Paris, celle du déposant)
     * @param encrypted vrai si le fichier est compressé puis chiffré ({@code .xml.gz.gpg}), faux pour le XML clair
     */
    public static String fileName(int year, String declarant, int sequence, LocalDateTime createdAt,
                                  boolean encrypted) {
        if (year < 2023 || year > 9999) {
            throw new IllegalArgumentException("Millésime invalide");
        }
        if (declarant == null || !declarant.matches("[A-Za-z0-9]{9}")) {
            throw new IllegalArgumentException("Identifiant du déclarant invalide (SIREN ou IDSP sur 9 caractères)");
        }
        if (sequence < 0 || sequence > 999) {
            throw new IllegalArgumentException("Numéro d'ordre invalide (0 à 999)");
        }
        String base = FIXED_VALUE + "_" + year + "_" + declarant + "_" + String.format("%03d", sequence) + "_"
                + createdAt.format(TIMESTAMP) + ".xml";
        return encrypted ? base + ".gz.gpg" : base;
    }
}
