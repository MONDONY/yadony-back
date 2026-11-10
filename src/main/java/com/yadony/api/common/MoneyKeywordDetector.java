package com.yadony.api.common;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Repère, dans un texte libre (description d'un colis), un mot qui évoque l'envoi d'argent :
 * espèces, chèques, cartes bancaires… interdits par l'article 4 des CGU. Ce n'est qu'un indice :
 * le colis n'est jamais bloqué, il remonte à la modération.
 *
 * <p>Règle : texte mis en minuscules et sans accents, ponctuation et apostrophes traitées comme
 * des séparateurs, terme reconnu en mot entier (« argentine », « argenté » ne déclenchent pas),
 * un ou plusieurs espaces entre les mots d'un terme composé.
 *
 * <p><b>Liste dupliquée dans l'app</b> ({@code dony_app}, détecteur qui affiche l'avertissement
 * sous le champ description) : garder les deux synchronisées.
 */
public final class MoneyKeywordDetector {

    /** Termes déjà normalisés. Ni « enveloppe », ni « billets », ni « espèce » ou « devise » au singulier : trop de faux positifs. */
    static final List<String> TERMS = List.of(
            "argent", "especes", "cash",
            "billet de banque", "billets de banque", "liasse", "liasses", "devises",
            "cheque", "cheques", "carte bancaire", "cartes bancaires", "carte prepayee", "cartes prepayees",
            "money", "banknote", "banknotes", "bank note", "bank notes",
            "prepaid card", "prepaid cards", "bank card", "bank cards");

    private static final List<Pattern> PATTERNS = TERMS.stream()
            .map(term -> Pattern.compile("(?<![\\p{L}\\p{N}])"
                    + String.join("\\s+", term.split(" "))
                    + "(?![\\p{L}\\p{N}])"))
            .toList();

    private MoneyKeywordDetector() {
    }

    /** Premier terme trouvé (dans l'ordre de {@link #TERMS}), ou vide. */
    public static Optional<String> firstMatch(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        for (int i = 0; i < PATTERNS.size(); i++) {
            if (PATTERNS.get(i).matcher(normalized).find()) {
                return Optional.of(TERMS.get(i));
            }
        }
        return Optional.empty();
    }

    static String normalize(String text) {
        String withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return withoutAccents.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
    }
}
