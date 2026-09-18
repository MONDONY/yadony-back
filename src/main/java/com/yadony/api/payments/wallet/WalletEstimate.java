package com.yadony.api.payments.wallet;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Estimation, dans la devise active, des soldes détenus dans toutes les devises
 * (cf. {@link WalletEstimateService}). Purement informatif : aucun argent n'est converti.
 *
 * <p>{@code inActiveByCurrency} : code devise (majuscules) → équivalent en devise active,
 * sans les devises dont le taux manque. {@code total} : somme des équivalents, {@code null}
 * si aucune devise n'a pu être convertie. {@code complete} : {@code false} dès qu'une devise
 * détenue a été exclue faute de taux.
 */
public record WalletEstimate(Map<String, BigDecimal> inActiveByCurrency,
                             BigDecimal total,
                             boolean complete) {
}
