package com.yadony.api.dac7;

import com.yadony.api.payments.currency.CountryCatalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pays de résidence que le voyageur peut déclarer pour DAC7 (collecte ciblée, relecture I6) : les pays du catalogue de
 * l'app ({@link CountryCatalog}) ET les 27 États membres de l'Union ({@link Dac7ResidencyPolicy#EU_MEMBER_STATES}),
 * dont BG, CZ, DK, HU, PL, RO et SE, absents du catalogue de paiement. Catalogue SÉPARÉ : {@link CountryCatalog} reste
 * la table pays vers devise et rails de paiement, inchangée ; un pays déclaré ici ne va que dans
 * {@code users.tax_declared_residence_country}, jamais dans {@code users.country}.
 */
public final class Dac7ResidenceCountries {

    /** Codes ISO 3166-1 alpha-2, triés. */
    public static final List<String> CHOICES;

    static {
        Set<String> codes = new TreeSet<>(CountryCatalog.all());
        codes.addAll(Dac7ResidencyPolicy.EU_MEMBER_STATES);
        CHOICES = List.copyOf(codes);
    }

    private Dac7ResidenceCountries() {
    }

    /** Code normalisé (majuscules) s'il fait partie des choix, sinon {@code null}. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        return CHOICES.contains(code) ? code : null;
    }
}
