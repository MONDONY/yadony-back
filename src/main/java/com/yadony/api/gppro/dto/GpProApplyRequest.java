package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProApplicationSource;
import com.yadony.api.gppro.GpProTravelFrequency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Demande du badge GP Pro. {@code commercialName} est la raison sociale. Les trois champs entreprise
 * ({@code commercialName}, {@code businessRegistrationNumber}, {@code businessCountry}) vont ensemble : dès que
 * le numéro ou le pays est présent, les trois sont obligatoires ({@code gp-pro-business-incomplete}). Une
 * ancienne app qui n'envoie ni numéro ni pays garde le comportement d'avant. Un champ vide ou blanc vaut absent
 * (donc incomplet si l'autre est présent) ; une erreur de format (numéro trop court, pays en minuscules) reste le 422
 * générique de validation. Le numéro est chiffré au repos et
 * n'apparaît dans aucun audit, journal ni message d'erreur.
 */
public record GpProApplyRequest(
        @NotNull GpProTravelFrequency travelFrequency,
        @Size(max = 80) String commercialName,
        @Size(max = 4) List<UUID> documentIds,
        @NotNull GpProApplicationSource source,
        @Pattern(regexp = "^(\\s*|[A-Za-z0-9][A-Za-z0-9 ./-]{3,39})$") String businessRegistrationNumber,
        @Pattern(regexp = "^([A-Z]{2}|\\s*)$") String businessCountry) {

    /** Sans données d'entreprise (anciennes apps). */
    public GpProApplyRequest(GpProTravelFrequency travelFrequency, String commercialName,
                             List<UUID> documentIds, GpProApplicationSource source) {
        this(travelFrequency, commercialName, documentIds, source, null, null);
    }
}
