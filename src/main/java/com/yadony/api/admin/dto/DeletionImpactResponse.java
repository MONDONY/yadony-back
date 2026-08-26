package com.yadony.api.admin.dto;

import java.util.List;
import java.util.UUID;

/**
 * Ce que la suppression d'un compte implique, tel que le back-office l'affiche.
 *
 * <p>{@code severity} et {@code code} sont des chaînes stables : le libellé est traduit côté
 * front, pour que l'ajout d'un contributeur n'oblige pas à redéployer le back pour un texte.
 */
public record DeletionImpactResponse(boolean blocked, List<Finding> findings) {

    public record Finding(String severity, String code, int count, List<Party> parties) {}

    public record Party(UUID userId, String displayName, UUID relatedEntityId) {}
}
