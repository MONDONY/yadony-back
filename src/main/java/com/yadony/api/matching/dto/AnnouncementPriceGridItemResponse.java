package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AnnouncementPriceGridItemResponse(
    UUID id,
    String label,
    BigDecimal unitPriceNet,
    BigDecimal unitPriceDisplay,
    /**
     * Équivalent ESTIMÉ de {@code unitPriceDisplay} dans la devise active du lecteur,
     * au taux courant — repère de lecture (« environ »), jamais le montant échangé.
     * {@code null} quand le lecteur lit déjà dans la devise de l'annonce.
     */
    BigDecimal convertedUnitPriceDisplay
) {
    /** Constructeur de compatibilité : sans équivalent converti (chemins d'écriture, tests). */
    public AnnouncementPriceGridItemResponse(UUID id, String label,
                                             BigDecimal unitPriceNet, BigDecimal unitPriceDisplay) {
        this(id, label, unitPriceNet, unitPriceDisplay, null);
    }

    public AnnouncementPriceGridItemResponse withConvertedDisplay(BigDecimal convertedUnitPriceDisplay) {
        return new AnnouncementPriceGridItemResponse(id, label, unitPriceNet, unitPriceDisplay,
                convertedUnitPriceDisplay);
    }
}
