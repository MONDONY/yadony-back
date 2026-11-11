package com.yadony.api.dac7.dto;

/**
 * {@code POST /admin/dac7/exports}.
 *
 * @param mode     {@code FULL} ou {@code PARTIAL}
 * @param sequence facultatif (ajout au contrat) : numéro d'ordre DGFiP de la déclaration pour ce millésime, 0 pour la
 *                 première (défaut), 1 pour la deuxième…
 * @param includeAlreadyExported facultatif : reprendre les voyageurs retenus dans un fichier DÉPOSÉ de l'année. Faux
 *                 par défaut : ils sont écartés (raison {@code ALREADY_DEPOSITED}), en FULL comme en PARTIAL. Vrai :
 *                 409 {@code dac7-already-deposited-conflict} s'il y en a (ce serait une rectificative, hors lot)
 */
public record Dac7ExportRequest(Integer year, String mode, Integer sequence, Boolean includeAlreadyExported) {

    public Dac7ExportRequest(Integer year, String mode, Integer sequence) {
        this(year, mode, sequence, null);
    }
}
