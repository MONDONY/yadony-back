package com.yadony.api.admin.dto;

import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Contexte d'une alerte ouverte dans le poste de traitement : les autres alertes qui visent le même
 * paiement ou le même colis, et les gestes tracés sur celle-ci (résolution, réouverture, report).
 */
public record AdminAlertContextResponse(List<RelatedAlert> related, List<HistoryEntry> history) {

    public record RelatedAlert(UUID id, String type, String severity, boolean resolved,
                               LocalDateTime createdAt, OffsetDateTime resolvedAt) {}
}
