package com.yadony.api.admin.dto;

import com.yadony.api.common.PageResponse;

/**
 * Onglet Tarification : grille en vigueur, grille programmée (ou {@code null}) et historique
 * paginé, annulées comprises.
 */
public record CommissionGridListResponse(CommissionGridAdminResponse current, CommissionGridAdminResponse scheduled,
                                         PageResponse<CommissionGridAdminResponse> history) {
}
