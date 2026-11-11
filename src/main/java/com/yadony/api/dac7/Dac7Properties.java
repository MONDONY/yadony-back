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
 *                              déclaration de janvier). Au moins 2 : sous ce plancher, DAC7 allumé
 *                              refuse de démarrer, éteint la valeur est ramenée à 2. Durée à confirmer par
 *                              l'expert-comptable.
 */
@ConfigurationProperties(prefix = "yadony.dac7")
public record Dac7Properties(Boolean enabled, List<String> partnerJurisdictions, Integer retentionYears) {

    static final int DEFAULT_RETENTION_YEARS = 2;
    /**
     * Plancher : avec 1, la fenêtre se réduit à l'année courante et la purge du 1er janvier effacerait le NIF d'un
     * compte supprimé payé l'année précédente, avant la déclaration de fin janvier.
     */
    static final int MIN_RETENTION_YEARS = 2;

    /** DAC7 allumé, une conservation sous {@link #MIN_RETENTION_YEARS} ans refuse le démarrage. */
    @ConstructorBinding
    public Dac7Properties {
        if (Boolean.TRUE.equals(enabled) && retentionYears != null && retentionYears < MIN_RETENTION_YEARS) {
            throw new IllegalArgumentException("yadony.dac7.retention-years : au moins " + MIN_RETENTION_YEARS
                    + " années civiles quand DAC7 est allumé (valeur reçue : " + retentionYears + ")");
        }
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

    /** Au moins 2 (année courante et précédente, ce qu'exige la déclaration de janvier) ; défaut 2. */
    public int retentionYearsOrDefault() {
        return retentionYears == null || retentionYears < MIN_RETENTION_YEARS ? DEFAULT_RETENTION_YEARS : retentionYears;
    }
}
