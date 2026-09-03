package com.yadony.api.toolkit;

/**
 * Les cinq outils qu'un utilisateur prépare une fois et réutilise à chaque
 * publication. L'ordre de déclaration est l'ordre du contrat API et celui du
 * « prochain outil à remplir » côté app : ne pas le réordonner.
 */
public enum ToolKey {
    ADDRESSES("addresses"),
    RECIPIENTS("recipients"),
    ALERTS("alerts"),
    TRIP_TEMPLATES("trip_templates"),
    PRICE_GRID("price_grid");

    private final String apiKey;

    ToolKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String apiKey() {
        return apiKey;
    }
}
