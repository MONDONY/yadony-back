package com.yadony.api.payments.reconciliation;

import java.util.List;

/**
 * Bilan d'un rapprochement : les écarts trouvés, le nombre d'objets effectivement comparés, et
 * le nombre d'objets que le prestataire n'a pas pu confirmer (panne, délai) — à revoir au
 * passage suivant, jamais comptés comme des écarts.
 */
public record ReconciliationResult(List<ReconciliationMismatch> mismatches, int checked, int errors) {
}
