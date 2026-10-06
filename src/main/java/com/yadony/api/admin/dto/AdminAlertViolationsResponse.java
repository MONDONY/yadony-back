package com.yadony.api.admin.dto;

import com.yadony.api.payments.integrity.MoneyIntegrityMonitor;

import java.util.List;
import java.util.Map;

/**
 * Lignes actuellement en faute d'une règle de cohérence de l'argent.
 *
 * @param total nombre total de lignes en faute (peut dépasser {@code rows.size()})
 * @param rows  extrait des lignes, colonnes SQL telles quelles (snake_case)
 */
public record AdminAlertViolationsResponse(
        String invariant,
        String title,
        String severity,
        long total,
        List<Map<String, Object>> rows
) {
    public static AdminAlertViolationsResponse from(MoneyIntegrityMonitor.Inspection inspection) {
        return new AdminAlertViolationsResponse(inspection.code(), inspection.title(), inspection.severity(),
                inspection.total(), inspection.rows());
    }
}
