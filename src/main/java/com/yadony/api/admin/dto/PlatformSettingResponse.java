package com.yadony.api.admin.dto;

import com.yadony.api.config.PlatformSettingType;
import com.yadony.api.config.PlatformSettingView;

import java.time.LocalDateTime;

/**
 * Un reglage plateforme tel que le back-office le consomme.
 *
 * <p>La valeur est serialisee en texte quel que soit son type reel : {@code type} sert
 * d'indice d'affichage et de validation cote interface, pas de coercition.
 *
 * <p>⚠️ {@code spring.jackson.default-property-inclusion: NON_NULL} : {@code updatedAt} et
 * {@code updatedByEmail} sont <b>absents du JSON</b> tant que le reglage n'a jamais ete
 * modifie par un administrateur. Le front les traite deja comme optionnels.
 */
public record PlatformSettingResponse(
        String key,
        String value,
        PlatformSettingType type,
        LocalDateTime updatedAt,
        String updatedByEmail) {

    public static PlatformSettingResponse from(PlatformSettingView view, String updatedByEmail) {
        return new PlatformSettingResponse(
                view.key().key(), view.value(), view.key().type(),
                view.updatedAt(), updatedByEmail);
    }
}
