package com.yadony.api.payments.integrity;

/**
 * Une règle qui doit toujours être vraie quand l'argent circule, écrite comme une requête SQL
 * en lecture seule qui renvoie les lignes en faute : zéro ligne, la règle tient.
 *
 * @param code     identifiant stable (INV-01…), repris dans la métrique et le type d'alerte
 * @param title    ce que la règle vérifie, en clair
 * @param severity gravité d'une ligne en faute
 * @param sql      SELECT sans point-virgule final, compté par {@code SELECT count(*) FROM (…) q}
 */
public record MoneyInvariant(String code, String title, Severity severity, String sql) {

    public enum Severity { CRITIQUE, HAUTE, MOYENNE }
}
