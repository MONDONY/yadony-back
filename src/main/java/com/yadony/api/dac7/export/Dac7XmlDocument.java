package com.yadony.api.dac7.export;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Contenu d'un fichier DPI-DAC7 : l'opérateur (Yadony) et ses vendeurs. Déclaration initiale (DPI401, OECD1)
 * seulement : les rectificatives (DPI402, OECD2) et la déclaration néante (DPI403) ne sont pas construites.
 *
 * @param messageUniquePart partie unique du {@code MessageRefId} (70 caractères au plus)
 */
public record Dac7XmlDocument(int year, LocalDateTime timestamp, String messageUniquePart, Operator operator,
                              List<Seller> sellers) {

    /**
     * Opérateur de plateforme déclarant.
     *
     * @param siren SIREN (ou IDSP), aussi dans la balise TIN : c'est lui que la DGFiP rapproche de l'habilitation
     */
    public record Operator(String siren, String name, String businessName, String vat, String street,
                           String postCode, String city, String docRefUniquePart) {
    }

    /** Un vendeur et la partie unique de son {@code DocRefId}. */
    public record Seller(Dac7XmlSeller data, String docRefUniquePart) {
    }
}
