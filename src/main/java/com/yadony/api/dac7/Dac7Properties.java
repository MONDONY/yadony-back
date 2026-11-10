package com.yadony.api.dac7;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Déclaration DAC7 (lot 3a). Détecté par {@code @ConfigurationPropertiesScan}.
 *
 * @param enabled               interrupteur des endpoints voyageur {@code /travelers/me/tax-info} : éteint sauf
 *                              {@code true} explicite ({@code YADONY_DAC7_ENABLED}). Ne pas l'allumer avant la mise à
 *                              jour de la politique de confidentialité et des CGU (préavis de 30 jours).
 * @param partnerJurisdictions  États ou territoires PARTENAIRES (hors Union) dont les résidents sont déclarables, codes
 *                              ISO 3166-1 alpha-2. VIDE par défaut : la liste de l'arrêté du 26/12/2022 reste à
 *                              confirmer pays par pays (notamment CI, SN, ML, CM) par l'expert-comptable. Rien n'est
 *                              ajouté par supposition.
 */
@ConfigurationProperties(prefix = "yadony.dac7")
public record Dac7Properties(Boolean enabled, List<String> partnerJurisdictions) {

    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }

    public List<String> partnerJurisdictionsOrEmpty() {
        return partnerJurisdictions == null ? List.of() : partnerJurisdictions;
    }
}
