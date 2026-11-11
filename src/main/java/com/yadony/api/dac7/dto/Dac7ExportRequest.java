package com.yadony.api.dac7.dto;

/**
 * {@code POST /admin/dac7/exports}.
 *
 * @param mode     {@code FULL} ou {@code PARTIAL}
 * @param sequence facultatif (ajout au contrat) : numéro d'ordre DGFiP de la déclaration pour ce millésime, 0 pour la
 *                 première (défaut), 1 pour la deuxième…
 */
public record Dac7ExportRequest(Integer year, String mode, Integer sequence) {
}
