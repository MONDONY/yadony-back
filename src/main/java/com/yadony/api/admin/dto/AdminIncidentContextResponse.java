package com.yadony.api.admin.dto;

import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Contexte d'un incident ouvert dans le poste de traitement : les autres incidents (litiges et
 * déclarations d'absence) des deux parties, et les gestes tracés sur celui-ci.
 */
public record AdminIncidentContextResponse(List<RelatedIncident> related, List<HistoryEntry> history) {

    /**
     * @param kind  {@code DISPUTE} ou {@code NOSHOW}
     * @param type  type du litige, ou motif de la déclaration d'absence
     * @param scope portée de l'absence ({@code HANDOVER} ou {@code DELIVERY}), absente pour un litige
     * @param open  litige ouvert, ou absence qui attend encore une décision
     */
    public record RelatedIncident(String kind, UUID id, String type, String scope, boolean open,
                                  LocalDateTime createdAt) {}
}
