package com.yadony.api.dac7;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param retentionYears        années civiles pendant lesquelles le NIF d'un compte supprimé est gardé s'il a une
 *                              opération déclarable (défaut 2 : année courante et précédente, ce qu'exige la
 *                              déclaration de janvier). À confirmer par l'expert-comptable.
 */
@ConfigurationProperties(prefix = "yadony.dac7")
public record Dac7Properties(Boolean enabled, List<String> partnerJurisdictions, Integer retentionYears) {

    static final int DEFAULT_RETENTION_YEARS = 2;

    @ConstructorBinding
    public Dac7Properties {
    }

    public Dac7Properties(Boolean enabled, List<String> partnerJurisdictions) {
        this(enabled, partnerJurisdictions, null);
    }

    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }

    public List<String> partnerJurisdictionsOrEmpty() {
        return partnerJurisdictions == null ? List.of() : partnerJurisdictions;
    }

    /** Au moins 1 (l'année courante) ; défaut 2. */
    public int retentionYearsOrDefault() {
        return retentionYears == null || retentionYears < 1 ? DEFAULT_RETENTION_YEARS : retentionYears;
    }
}
