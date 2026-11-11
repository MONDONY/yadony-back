package com.yadony.api.dac7;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Taux de référence BCE pour DAC7 (lot 3b). Détecté par {@code @ConfigurationPropertiesScan}.
 *
 * @param fetchEnabled télécharger les taux historiques quand une page admin ou un export en a besoin (aucune tâche
 *                     planifiée). Faux dans les tests : aucun appel réseau.
 * @param histUrl      flux {@code eurofxref-hist.xml} de la BCE
 */
@ConfigurationProperties(prefix = "yadony.dac7.ecb")
public record Dac7EcbProperties(Boolean fetchEnabled, String histUrl) {

    static final String DEFAULT_HIST_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.xml";

    public boolean fetchEnabledOrDefault() {
        return !Boolean.FALSE.equals(fetchEnabled);
    }

    public String histUrlOrDefault() {
        return histUrl == null || histUrl.isBlank() ? DEFAULT_HIST_URL : histUrl;
    }
}
