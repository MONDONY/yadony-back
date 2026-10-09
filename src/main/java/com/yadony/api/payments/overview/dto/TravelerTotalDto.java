package com.yadony.api.payments.overview.dto;

import java.math.BigDecimal;

/**
 * Totaux voyageur d'une devise — jamais additionnés entre devises.
 *
 * @param upcoming         à recevoir : séquestre, garde, litige, retenu, versement en cours
 * @param releasedRecently libéré dans la fenêtre récente
 */
public record TravelerTotalDto(String currency, BigDecimal upcoming, BigDecimal releasedRecently) {
}
